# ETL

원본 데이터 적재·검수·그래프 빌드. 계획서 §5·§6 참조.

## 환경

**시스템 파이썬에 설치하지 않는다.** venv를 쓴다.

```bash
py -m venv .venv
./.venv/Scripts/python.exe -m pip install -r etl/requirements.txt
```

Linux/macOS는 `.venv/bin/python`.

## 원본 데이터

`csv/`는 저장소에 없다 (340MB, 최대 파일 154MB — GitHub 단일 파일 한도 초과).
출처와 수신 방법은 계획서 §3의 데이터 표를 따른다. 배치는 이렇게 둔다.

```
csv/
  장소/    식품_일반음식점_서울특별시.csv, 식품_휴게음식점_서울특별시.csv
  지하철/  도시철도열차운행시각표(역간 소요시간 + 시간대별 배차, 1~9호선),
           환승역거리 소요시간, 역사마스터, 역간거리 및 소요시간, 열차운행현황(대조용)
  버스/    노선마스터, 정류장마스터, 구간별 평균 운행시간(tpss_route_section_speedh_*),
           노선기본정보(배차간격, xlsx), 노선ID정보(조인키, xlsx)
```

키가 서로 다른 데이터를 잇는 경로가 둘 있다.

- 버스: `노선번호` ──[노선ID정보]──▶ `ROUTEID` ──▶ 구간 데이터 `노선_ID` (서울 693노선 100%)
- 지하철: 시각표 `(LINE, STATION_NM)` ──[노선명 매핑]──▶ 역사마스터 `(호선, 역사명)` (98.0%)
  역사마스터가 1호선 바깥을 경부선·경인선·경원선, 4호선을 안산선·과천선 등으로 담고 있어 매핑이 필요하다.

**인코딩이 파일마다 다르다.** 대부분 CP949(EUC-KR)인데 **열차운행시각표만 UTF-8 BOM**이다.
자동 감지에 기대지 말고 파일별로 지정할 것.

원본 좌표계가 둘로 갈린다 — POI는 **EPSG:5174**(`좌표정보(X)/(Y)`), 정류장·역사는
**EPSG:4326**(위도/경도). 저장은 전부 **EPSG:5186**으로 통일한다.

## ingest — 인허가 적재 (단일 진입점)

```bash
docker compose up -d db
docker compose --profile migrate run --rm flyway

./.venv/Scripts/python.exe -X utf8 -m etl.ingest --source all
```

```
CSV (CP949)  ──COPY──▶  poi_raw  ──변환·중복제거──▶  poi_staging
                                                        │ 검수 규칙
                                                        ▼
                                              validation_result
                                                        │ ERROR 제외분
                                                        ▼
                                                      poi
```

| 옵션 | |
|---|---|
| `--source food\|rest\|all` | 적재 대상 |
| `--limit N` | 앞 N행만 (시험용) |
| `--force` | 파일 해시가 같아도 다시 적재 |
| `--snapshot PATH` | 원본 경로 덮어쓰기. 다른 회차 스냅샷이나 실패 격리 시험용 |

**실측 (2026-09-15)**: 서울 전체 685,718행(일반 538,576 + 휴게 147,142) **2분 28초**.
serving `poi` 646,418건 — ACTIVE 154,669 / CLOSED 491,749.

### 알아둘 것

- **좌표 변환은 여기서만** 한다. DB는 `ST_Transform` 을 쓰지 않는다 (`verify_db_crs.py` 참조)
- staging 적재 직후 **`ANALYZE poi_staging` 이 필수**다. 트랜잭션 안이라 autoanalyze 가 손대지 못하는데,
  통계가 낡으면 `DUPLICATE_NAME_ADDR` 조인이 nested loop 로 풀려 **8분 넘게** 걸린다 (실측)
- `poi_staging` 은 현재·직전 회차만 남기고 정리한다. 안 그러면 회차마다 수십만 행씩 쌓여
  일일 자동 실행 열흘이면 500만 행이 된다
- 실패하면 회차가 FAILED 로 남고 **serving 은 무변경**이다 (단일 트랜잭션)

## verify_coords — 좌표계 판정 (D0)

인허가 좌표의 원본 좌표계를 앵커 대조로 판정한다. 어느 EPSG인지 파일에 적혀 있지 않고,
잘못 고르면 **전체가 같은 방향으로 수백 미터 밀려** 지도에서 눈으로는 잡히지 않는다.

```bash
./.venv/Scripts/python.exe -X utf8 -m etl.verify_coords --out etl/out/coord_verification.json
```

전체(50만 행) 기준 2분쯤 걸린다. 빠르게 보려면 `--nrows 200000`.

터미널이 한글을 못 찍으면 `-X utf8`과 함께 `chcp 65001`(Windows) 또는 출력을 파일로 받는다.

**결과 (2026-09-14)**: 원본은 5174 계열 확정(`lon_0 = 127.0028902777778`). datum shift 방식은
결과에 무관(이격 최대 21.3m). 상세는 계획서 §6 D0과 `out/coord_verification.json`.

### 앵커를 고칠 때

`ANCHORS`의 위경도는 **손으로 넣은 참조값**이다. 잔여 오차 ~124m의 대부분이 여기서 온다
(63빌딩 8.5m vs 고속터미널 206.2m). 오차가 크게 나오면 변환보다 이 표를 먼저 의심할 것.

## verify_crs_alignment — 좌표계 정합 교차 검증

POI(5174)와 버스 정류장(4326)을 5186으로 통일했을 때 실제로 겹치는지 확인한다.
앵커를 사람이 입력하지 않고 **정류장 11,494개를 기준으로** 쓰므로 `verify_coords`보다 독립적이다.

```bash
./.venv/Scripts/python.exe -X utf8 -m etl.verify_crs_alignment --out etl/out/crs_alignment.json
```

**결과 (2026-09-14)**: 채택 좌표계에서 POI→최근접 정류장 중앙 **80.5m**, 100m 이내 62.6%.
음성 대조군(5186으로 오인)은 88km. 정합 확인.

## verify_db_crs — ETL ↔ DB 좌표 변환 일치 확인

좌표 변환은 ETL(pyproj)에서만 하고 DB는 저장만 한다. 그래도 두 엔진이 같은 EPSG 코드를
다르게 해석하지 않는지는 확인해야 한다 — 어긋나면 반경 검색과 스냅 거리가 조용히 틀어진다.

```bash
docker compose up -d db
./.venv/Scripts/python.exe -X utf8 -m etl.verify_db_crs
```

**결과 (2026-09-15)**: 표본 1,000점 최대 차이 **0.000000m**.
pyproj(PROJ 9.4.1)과 PostGIS 3.5가 EPSG:5174→5186을 동일하게 해석한다.

DB 셋업 직후와 PROJ·PostGIS 버전을 올릴 때마다 돌린다. **허용치(1mm)를 늘려 통과시키지 말 것.**

## 테스트

```bash
./.venv/Scripts/python.exe -m pytest -q
```

원본 CSV에 의존하지 않는다 — CSV가 필요한 테스트는 CP949 픽스처를 그때그때 만든다.
