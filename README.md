# Where-We-Go!

출발지–목적지 이동시간 행렬 기반 모임 장소 비교 서비스.
공간 데이터 수집 · 검수 · 갱신 파이프라인 위에 구축한다.

> 개발 중. 설계와 진행 상황은 [프로젝트 계획서](kakaomobility-project-plan.md)를 본다.

## 지금까지

| | |
|---|---|
| 데이터 확보·검증 (D0) | ✅ 계획서 §6 D0 요약표 |
| DB 스키마 (raw/staging/serving 3계층) | ✅ Flyway V1·V2 |
| 인허가 적재 ETL | ✅ 서울 전체 685,718행 / 2분 28초 |
| 장소 API + 지도 화면 | ✅ 반경·상호명 검색 |
| 그래프 빌드 · 이동시간 행렬 | ⬜ |

## 실행

```bash
docker compose up -d db
docker compose --profile migrate run --rm flyway   # 스키마
docker compose up -d                               # 서버까지
```

→ http://localhost:8080

데이터를 넣으려면 원본 CSV 가 필요하다. `csv/` 는 저장소에 없다 — 340MB 이고 단일 파일이
GitHub 한도(100MB)를 넘는다. 출처와 배치는 [etl/README.md](etl/README.md) 를 본다.

```bash
py -m venv .venv
./.venv/Scripts/python.exe -m pip install -r etl/requirements.txt
./.venv/Scripts/python.exe -X utf8 -m etl.ingest --source all
```

### 개발 중에는

```bash
cd server && ./gradlew bootRun     # 서버 (JAVA_HOME 에 JDK 25)
cd web && npm run dev              # 프론트 HMR, :5173 에서 /api 를 8080 으로 프록시
```

### 카카오맵 키

외부 API 키나 활용신청이 필요한 구성요소가 없다. **배경지도만 키가 필요하고 나머지는 전부 파일이다.**

```bash
cp .env.example .env     # WWG_KAKAO_JS_KEY 를 채운다
docker compose up -d server
```

**저장소 루트의 `.env`** 에 둔다 — Docker Compose 가 자동으로 읽고, `gradlew bootRun` 도
`build.gradle` 이 같은 파일을 읽어 넘긴다. Spring Boot 자체는 `.env` 를 읽지 않으므로
`server/.env` 에 두면 동작하지 않는다.

카카오 개발자 콘솔의 **플랫폼 &gt; Web** 에 `http://localhost:8080` 과 `http://localhost:5173` 을
등록해야 한다. 등록하지 않으면 키가 맞아도 지도가 뜨지 않는다.

키가 없으면 지도 자리에 발급 안내가 뜨고 목록 검색은 그대로 동작한다.

## API

| | |
|---|---|
| `GET /api/v1/places/nearby` | 좌표 반경 검색 (`ST_DWithin` + GiST) |
| `GET /api/v1/places/search` | 상호명 부분 일치 (pg_trgm) |

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
