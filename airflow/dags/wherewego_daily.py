"""일일 받기·적재 — etl/run_daily.sh 를 Airflow DAG 로 옮긴 것. 로컬 학습용.

    fetch_food ──→ ingest_food ──┐
                                 ├──→ verify_counts
    fetch_rest ──→ ingest_rest ──┘

경계는 기존 코드의 단위를 그대로 따른다. 새로 쪼갠 곳이 없다.

- 받기는 파일 단위다. 소스끼리 독립이라 동시에 돈다.
- 적재는 소스별로 한 트랜잭션이다(ingest_source). 그 안의 8단계를 태스크로 쪼개면
  실패했을 때 rollback 으로 serving 을 지키는 보장이 사라진다 — 그래서 쪼개지 않는다.
- 검증은 ingest_run 을 읽기만 한다. 트랜잭션 밖이라 따로 떼어도 잃는 것이 없다.

cron 판(run_daily.sh)과 종료 코드의 뜻이 같다.

    fetch   4 = 받기 실패          → 빨강. 적재는 기존 파일로 그대로 돈다(trigger_rule)
    ingest  2 = 적재 실패          → 빨강
    ingest  3 = DB 에 닿지 못함     → 건너뜀(분홍). 실패가 아니다 — cron 판이 0 으로 바꾸는 것과 같다
"""

from __future__ import annotations

from datetime import timedelta

import pendulum
from airflow.exceptions import AirflowSkipException
from airflow.providers.standard.operators.bash import BashOperator
from airflow.providers.standard.operators.python import PythonOperator
from airflow.sdk import DAG

from etl.db import connect
from etl.sources import SOURCES

#: 이 DAG 이 다루는 소스. etl.sources.SOURCES 의 키다.
TARGETS = ["food", "rest"]

#: etl.ingest.EXIT_DB_UNAVAILABLE 과 같다. import 하지 않는 이유 — DAG 파일은 수십 초마다 다시
#: 파싱되는데 etl.ingest 는 pandas·pyproj 를 끌고 온다. 파싱이 무거워지면 스케줄러가 느려진다.
EXIT_DB_UNAVAILABLE = 3

#: 유효 건수가 직전 성공 회차보다 이만큼 넘게 줄면 이상으로 본다.
#: 하루치 변화는 0.1% 안팎이다. 30% 가 빠졌다면 원본이 잘렸거나 파싱이 깨진 것이다.
MAX_VALID_DROP = 0.30

#: ETL 코드가 붙어 있는 곳. 소스 경로가 작업 폴더 기준(csv/장소/…)이라 여기서 돈다.
WWG_DIR = "/opt/wwg"


def notify(context) -> None:
    ti = context["task_instance"]
    print(f"[ALERT] {ti.task_id} 실패 (시도 {ti.try_number}) · 회차 {context['ds']}")


def verify_counts(**context) -> None:
    """이번 DAG 실행에서 생긴 회차를 소스마다 확인한다. 읽기만 한다.

    적재 태스크가 빨강이어도 DAG 의 성패는 마지막 태스크가 정한다. 여기서 실패를 다시
    확인하지 않으면 적재가 깨진 날에도 DAG 은 초록으로 끝난다.
    """
    started = context["dag_run"].start_date
    try:
        conn = connect()
    except Exception as e:  # noqa: BLE001 — DB 가 꺼진 날은 적재도 건너뛰었다
        raise AirflowSkipException(f"DB 에 닿지 못해 검증을 건너뛴다: {e}") from e

    problems = []
    with conn:
        for key in TARGETS:
            code = SOURCES[key].code
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT id, status, rows_valid, error_message
                    FROM ingest_run
                    WHERE source = %s AND started_at >= %s
                    ORDER BY id DESC LIMIT 1
                    """,
                    (code, started),
                )
                row = cur.fetchone()
            if row is None:
                # 원본 파일이 없으면 회차를 만들기 전에 멈춘다(ingest_source 첫 줄).
                problems.append(f"{code}: 이번 실행의 회차가 없다 — 적재가 시작하지 못했다")
                continue

            run_id, status, valid, error = row
            print(f"{code}: 회차 {run_id} {status} · 유효 {valid or 0:,}")
            if status == "FAILED":
                problems.append(f"{code}: 회차 {run_id} FAILED — {error}")
            elif status == "SUCCESS":
                with conn.cursor() as cur:
                    cur.execute(
                        """
                        SELECT rows_valid FROM ingest_run
                        WHERE source = %s AND status = 'SUCCESS' AND id < %s
                        ORDER BY id DESC LIMIT 1
                        """,
                        (code, run_id),
                    )
                    prev = cur.fetchone()
                if prev and prev[0] and valid < prev[0] * (1 - MAX_VALID_DROP):
                    problems.append(
                        f"{code}: 유효 건수가 {prev[0]:,} → {valid:,} "
                        f"({(1 - valid / prev[0]) * 100:.0f}% 감소)"
                    )

    if problems:
        raise ValueError("검증 실패\n  " + "\n  ".join(problems))


with DAG(
    dag_id="wherewego_daily",
    description="인허가 CSV 받기 → 소스별 적재 → 회차 검증",
    schedule="0 22 * * *",
    start_date=pendulum.datetime(2026, 10, 1, tz="Asia/Seoul"),
    catchup=False,
    # cron 판은 flock 으로 막았다. 같은 회차를 두 실행이 다투지 않게 한다.
    max_active_runs=1,
    default_args={
        "retries": 2,
        "retry_delay": timedelta(minutes=1),
        "on_failure_callback": notify,
    },
    tags=["wherewego", "etl"],
) as dag:
    verify = PythonOperator(
        task_id="verify_counts",
        python_callable=verify_counts,
        # 적재가 실패해도 돈다. 실패를 보고하는 것이 이 태스크의 일이다.
        trigger_rule="all_done",
        retries=0,
    )

    for key in TARGETS:
        fetch = BashOperator(
            task_id=f"fetch_{key}",
            bash_command=f"python -m etl.fetch --source {key}",
            cwd=WWG_DIR,
        )
        ingest = BashOperator(
            task_id=f"ingest_{key}",
            bash_command=f"python -m etl.ingest --source {key} --wait-db 60",
            cwd=WWG_DIR,
            # 받기가 실패해도 기존 파일로 적재한다 — run_daily.sh 와 같다.
            trigger_rule="all_done",
            skip_on_exit_code=EXIT_DB_UNAVAILABLE,
            # 다시 시도하지 않는다. 시도마다 FAILED 회차가 하나씩 쌓이고, 원본이 그대로면
            # 두 번째부터는 SKIPPED 로 끝나 실패를 가린다.
            retries=0,
            pool="wwg_db",
        )
        fetch >> ingest >> verify
