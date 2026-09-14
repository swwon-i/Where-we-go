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
  지하철/  역간거리 및 소요시간, 환승역거리 소요시간, 역사마스터
  버스/    노선마스터, 구간별 평균 운행시간(tpss_route_section_speedh_*), 정류장코드
```

인허가 CSV는 **CP949(EUC-KR)**다. UTF-8로 열면 깨진다.

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

## 테스트

```bash
./.venv/Scripts/python.exe -m pytest -q
```

원본 CSV에 의존하지 않는다 — CSV가 필요한 테스트는 CP949 픽스처를 그때그때 만든다.
