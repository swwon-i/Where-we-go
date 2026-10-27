# airflow — 일일 적재를 DAG 로 (로컬 학습용)

서버의 일일 실행은 **cron + `etl/run_daily.sh`** 그대로다. 이 폴더는 같은 일을 Airflow 로 옮겨
노트북에서 돌려 보는 곳이다. 서버에는 올리지 않는다.

```
fetch_food ──→ ingest_food ──┐
                             ├──→ verify_counts
fetch_rest ──→ ingest_rest ──┘
```

| 태스크 | 하는 일 | 실패하면 |
|---|---|---|
| `fetch_*` | `etl.fetch --source …` 로 원본 CSV 받기 | 빨강. 1분 뒤 2번 다시 시도. 그래도 안 되면 적재는 **기존 파일로** 돈다 |
| `ingest_*` | `etl.ingest --source …` 로 소스 하나 적재 (한 트랜잭션) | 빨강. 다시 시도하지 않는다. DB 에 닿지 못하면(코드 3) 분홍(건너뜀) |
| `verify_counts` | 이번 실행의 `ingest_run` 회차를 읽어 FAILED · 회차 없음 · 유효 건수 30% 넘게 감소를 잡는다 | 빨강 → DAG 실행 전체가 실패 |

## 띄우기

Docker Desktop 에 메모리를 **4GB 이상** 준다(Airflow 공식 최소). 적재가 판다스로 수십만 행을 올리므로 넉넉할수록 좋다.

```bash
docker compose up -d db                # 저장소 루트에서. 적재할 DB 와 네트워크가 먼저 있어야 한다
cd airflow
docker compose up airflow-init         # 처음 한 번 — 메타데이터 DB · 관리자 계정 · 풀(wwg_db)
docker compose up -d
```

<http://localhost:8081> · `airflow` / `airflow`. DAG 은 멈춘 채로 올라온다 — 화면에서 켜거나
**▶ Trigger** 로 바로 한 번 돌린다. 8080 은 wwg-server 가 쓰므로 8081 이다.

| | |
|---|---|
| 끄기 | `docker compose down` (회차 기록은 wwg-db 에 남는다) |
| 메타데이터까지 지우기 | `docker compose down -v` |
| 태스크 하나만 시험 | `docker compose exec airflow-scheduler airflow tasks test wherewego_daily verify_counts 2026-10-27` |

## cron 판과 다른 점

| | cron (`run_daily.sh`) | Airflow |
|---|---|---|
| 단위 | 스크립트 하나 — 받기 전부 → 적재 전부 | 소스별 태스크. food 받기가 실패해도 rest 는 끝까지 간다 |
| 다시 시도 | 없음 | 받기만 2번. 적재는 시도마다 FAILED 회차가 쌓여 하지 않는다 |
| 동시 실행 방어 | `flock` | `max_active_runs=1` |
| 적재 동시성 | 차례대로 | 풀 `wwg_db` 1칸 — 받기는 동시에, 적재는 하나씩 (메모리 · category upsert) |
| 알림 | healthchecks.io | `on_failure_callback` (지금은 로그만) |
| 결과 확인 | 종료 코드 | `verify_counts` 가 `ingest_run` 을 직접 읽는다 |
| 코드 반영 | 이미지 다시 빌드 (run_daily.sh 가 매번) | `etl/` 을 볼륨으로 붙여 고치면 바로 |

**적재 안의 8단계는 쪼개지 않았다.** `ingest_source` 는 raw → staging → 검수 → serving upsert 까지
한 트랜잭션이고, 실패하면 `rollback()` 으로 serving 을 직전 상태로 지킨다. 태스크로 나누면
단계마다 커밋해야 해서 그 보장이 사라진다.

## 왜 서버는 cron 인가

| | |
|---|---|
| 서버 메모리 | 2GB 에 PostgreSQL · Spring 서버 · ETL. 적재도 청크로 줄여 겨우 맞췄다 |
| Airflow | 공식 최소 4GB. 스케줄러 · API 서버 · DAG 처리기 · 메타데이터 DB 가 상주한다 |
| 일의 크기 | 하루 한 번, 태스크 다섯 개. 의존성이 단순해 cron 의 실패를 종료 코드 · flock · healthchecks.io 로 메웠다 |

## 함정

- `EXIT_DB_UNAVAILABLE = 3` 을 DAG 에 따로 적었다. `etl.ingest` 를 import 하면 DAG 파일을 파싱할 때마다
  pandas · pyproj 를 불러 스케줄러가 느려진다. **`etl/ingest.py` 의 값을 바꾸면 여기도 바꾼다.**
- DB 비밀번호는 저장소 루트 `.env` 의 `WWG_DB_PASSWORD` 를 `env_file` 로 받는다. compose 의 `${…}` 치환은
  이 폴더의 `.env` 만 보기 때문이다. 처음엔 이걸 몰라 기본값으로 붙다가 `password authentication failed` 가 났고,
  적재가 그걸 "DB 꺼짐(3)"으로 읽어 **건너뜀 → DAG 초록**으로 끝났다.
- 이미지 빌드 컨텍스트는 `etl/` 이다. 루트 `.dockerignore` 가 `etl/` 을 빼 두었기 때문이다.
- 저장소 루트의 compose 가 만든 네트워크 `where-we-go_default` 에 붙는다. 루트에서 `docker compose up -d db` 를
  한 번도 안 했으면 이 네트워크가 없어 Airflow 가 뜨지 않는다.
