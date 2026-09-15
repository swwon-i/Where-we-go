"""DB 접속 공통."""

from __future__ import annotations

import os

import psycopg

#: docker-compose.yml 의 값과 같다. 환경변수로 덮어쓸 수 있다.
DEFAULT_DSN = os.environ.get(
    "WWG_DSN", "postgresql://wherewego:wherewego@localhost:5432/wherewego"
)


def connect(dsn: str | None = None) -> psycopg.Connection:
    """자동 커밋 없이 연다 — 적재는 트랜잭션 단위로 통제한다."""
    return psycopg.connect(dsn or DEFAULT_DSN, connect_timeout=10)
