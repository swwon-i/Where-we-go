# Where-We-Go!

출발지–목적지 이동시간 행렬 기반 모임 장소 비교 서비스.
공간 데이터 수집 · 검수 · 갱신 파이프라인 위에 구축한다.

> 개발 중. 설계와 진행 상황은 [프로젝트 계획서](kakaomobility-project-plan.md)를 본다.

## 지금까지

| | |
|---|---|
| 데이터 확보·검증 (D0) | ✅ 완료 — 계획서 §6 D0 요약표 |
| DB 스키마 (raw/staging/serving 3계층) | ✅ V1·V2 마이그레이션 |
| 인허가 적재 ETL | ⬜ |
| 장소 API + 지도 화면 | ⬜ |

## 실행

원본 데이터(`csv/`)는 저장소에 없다 — 340MB이고 단일 파일이 GitHub 한도(100MB)를 넘는다.
출처와 배치는 [etl/README.md](etl/README.md)를 본다.

```bash
# DB
docker compose up -d db
docker compose --profile migrate run --rm flyway

# ETL 환경
py -m venv .venv
./.venv/Scripts/python.exe -m pip install -r etl/requirements.txt

# 검증
./.venv/Scripts/python.exe -m pytest -q
./.venv/Scripts/python.exe -X utf8 -m etl.verify_db_crs
```

외부 API 키나 활용신청이 필요한 구성요소가 없다. 배경지도만 키가 필요하고 나머지는 전부 파일이다.

## 좌표계

| 데이터 | 원본 | 저장 |
|---|---|---|
| POI (인허가) | EPSG:5174 | → **5186** |
| 버스 정류장 · 지하철 역사 | EPSG:4326 | → **5186** |

5186은 미터 단위라 반경 검색과 스냅 거리를 그대로 쓴다. API 응답에서만 4326으로 변환한다.
**변환은 ETL에서만 수행한다** — 실측으로 pyproj와 PostGIS가 일치함을 확인했지만(차이 0.000000m),
PROJ 버전에 따라 갈릴 수 있는 지점이라 엔진을 하나로 고정한다.

## 스택

PostgreSQL 17 + PostGIS 3.5 · Flyway · Python (pandas/pyproj) · Spring Boot + JDK 25 · React + Vite
