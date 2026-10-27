"""DB 접속 공통."""

from __future__ import annotations

import os

import psycopg

#: docker-compose.yml 의 값과 같다. 환경변수로 덮어쓸 수 있다.
DEFAULT_DSN = os.environ.get(
    "WWG_DSN", "postgresql://wherewego:wherewego@localhost:5432/wherewego"
)


def connect(dsn: str | None = None) -> psycopg.Connection:
    """자동 커밋 없이 연다 — 적재는 트랜잭션 단위로 통제한다.

    `WWG_DB_PASSWORD` 가 환경에 있으면 접속 문자열의 비밀번호보다 앞선다. 저장소 루트 .env 를
    env_file 로만 받을 수 있는 곳(airflow/)에서 비밀번호를 접속 문자열에 다시 적지 않으려는 것이다.
    """
    extra = {}
    if password := os.environ.get("WWG_DB_PASSWORD"):
        extra["password"] = password
    return psycopg.connect(dsn or DEFAULT_DSN, connect_timeout=10, **extra)
