"""인허가 CSV 적재 — 단일 진입점.

    CSV (CP949)
        │  COPY 벌크 로드
        ▼
    poi_raw          원본 그대로. 제약 없음
        │  타입 변환 · 좌표 변환(5174 → 5186) · 중복 제거
        ▼
    poi_staging
        │  검수 규칙 (etl/validate/rules/*.sql)
        ▼
    validation_result
        │  ERROR 제외분만 UPSERT
        ▼
    poi              API 가 읽는 유일한 테이블

원칙 (계획서 §6·§7)
-------------------
* **좌표 변환은 여기서만** 한다. DB는 저장만 하고 ST_Transform 을 쓰지 않는다 —
  두 엔진이 EPSG:5174 를 다르게 해석할 여지를 없앤다. (`verify_db_crs.py` 로 확인)
* serving 은 **항상 UPSERT**. 테이블 교체(swap)를 쓰지 않는다 —
  전체분 재적재가 일 변동분으로 갱신한 영업상태를 덮어쓰기 때문이다.
* 폐업은 삭제가 아니라 `status='CLOSED'`. 소멸(파일에서 사라짐)은 별개 사건으로 센다.
* 실패하면 회차를 FAILED 로 남기고 **serving 은 건드리지 않는다**.

사용법
-----
    python -m etl.ingest --source all
    python -m etl.ingest --source food --limit 50000
    python -m etl.ingest --source all --force      # 해시가 같아도 다시 적재
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import io
import sys
import time
from dataclasses import replace
from datetime import date, datetime
from pathlib import Path

import numpy as np
import pandas as pd
from pyproj import CRS, Transformer

from etl.db import connect
from etl.sources import (
    ACTIVE_STATUS_NAMES,
    KOREAN_HEADER,
    RAW_COLUMNS,
    SOURCE_EPSG,
    SOURCES,
    TARGET_EPSG,
    Source,
)

RULES_DIR = Path(__file__).parent / "validate" / "rules"

#: 파일 기반 규칙. 순서는 보고용이며 서로 독립이다.
FILE_RULES = [
    "NAME_MISSING",
    "COORD_MISSING",
    "STATUS_DATE_CONFLICT",
    "DATE_LOGIC_ERROR",
    "DUPLICATE_NAME_ADDR",
]

#: CSV → poi_raw COPY 청크 크기
CHUNK_ROWS = 50_000

#: DB 에 닿지 못해 적재를 시작조차 못한 경우. 스케줄러가 '실패'와 구분할 수 있게 별도 코드를 준다.
EXIT_DB_UNAVAILABLE = 3

#: DB 를 기다릴 때 다시 시도하는 간격(초).
DB_RETRY_INTERVAL_SEC = 15


def wait_for_db(
    wait_sec: float,
    interval_sec: float = DB_RETRY_INTERVAL_SEC,
    *,
    connect_fn=None,
    sleep=time.sleep,
    clock=time.monotonic,
) -> tuple[bool, int, float, Exception | None]:
    """DB 에 닿을 때까지 기다린다. `(닿았는가, 시도 횟수, 걸린 초, 마지막 오류)`.

    일일 적재가 절전에서 깨어나자마자 돌면 Docker·WSL 이 아직 올라오지 않아 첫 접속이
    실패한다. 한 번만 보고 물러나면 **도커를 안 켠 날과 구분되지 않는 채** 그날 회차를 잃는다.
    `wait_sec` 동안 `interval_sec` 간격으로 다시 시도한다. 0 이면 한 번만 본다.

    `connect_fn`·`sleep`·`clock` 은 시험용으로 바꿔 끼운다.
    """
    connect_fn = connect_fn or connect
    start = clock()
    attempts = 0
    while True:
        attempts += 1
        try:
            connect_fn().close()
            return True, attempts, clock() - start, None
        except Exception as e:  # noqa: BLE001 — 접속 실패 원인을 그대로 돌려준다
            last = e
        elapsed = clock() - start
        if elapsed + interval_sec > wait_sec:
            return False, attempts, elapsed, last
        sleep(interval_sec)

#: 서울을 넉넉히 감싸는 사각형(5186). **실제 행정경계가 아니다.**
#: 검수 규칙이 아니라 좌표 변환이 깨졌는지 보는 계기판이며, 개별 건수가 아니라 자릿수를 본다.
SEOUL_5186_BOUNDS = (170_000.0, 520_000.0, 230_000.0, 580_000.0)

#: 이 비율을 넘으면 변환이 깨진 것으로 본다. 정상이면 0.001% 수준이다.
OUTSIDE_ALARM_RATIO = 0.01


# ──────────────────────────────────────────────────────────────────────────────
# 순수 함수
# ──────────────────────────────────────────────────────────────────────────────


def file_hash(path: Path, chunk: int = 1 << 20) -> str:
    """파일 SHA-256. 조건부 요청(ETag/Last-Modified)이 안 되는 서버를 대비한 변경 감지 수단."""
    h = hashlib.sha256()
    with path.open("rb") as f:
        while block := f.read(chunk):
            h.update(block)
    return h.hexdigest()


def parse_date(value: object) -> date | None:
    """인허가 CSV의 날짜. `YYYY-MM-DD` 와 `YYYYMMDD` 가 섞여 있고 공백·빈칸이 흔하다."""
    if not isinstance(value, str):
        return None
    s = value.strip()
    if not s:
        return None
    for fmt in ("%Y-%m-%d", "%Y%m%d", "%Y-%m-%d %H:%M:%S"):
        try:
            return datetime.strptime(s[: len(fmt) + 2].strip(), fmt).date()
        except ValueError:
            continue
    return None


def is_active(status_name: object) -> bool:
    """영업상태명이 영업 중을 뜻하는가."""
    return isinstance(status_name, str) and status_name.strip() in ACTIVE_STATUS_NAMES


def normalize_category(raw: object) -> str | None:
    """업태구분명을 정규화 코드로. 표기 흔들림(공백·중점)을 흡수한다."""
    if not isinstance(raw, str):
        return None
    s = raw.strip().replace(" ", "").replace("·", "")
    return s or None


def make_transformer() -> Transformer:
    """5174 → 5186. always_xy 이므로 (동, 북) 순서."""
    return Transformer.from_crs(
        CRS.from_epsg(SOURCE_EPSG), CRS.from_epsg(TARGET_EPSG), always_xy=True
    )


def transform_coords(
    x: np.ndarray, y: np.ndarray, transformer: Transformer | None = None
) -> tuple[np.ndarray, np.ndarray]:
    """5174 좌표 배열을 5186 으로. 결측(NaN)은 NaN 으로 남는다."""
    t = transformer or make_transformer()
    east, north = t.transform(x, y)
    return np.asarray(east, dtype=float), np.asarray(north, dtype=float)


def count_outside_seoul(east: np.ndarray, north: np.ndarray) -> int:
    """서울 경계 밖 좌표 수. 검수 규칙이 아니라 변환 상태를 보는 계기판이다."""
    x0, y0, x1, y1 = SEOUL_5186_BOUNDS
    ok = np.isfinite(east) & np.isfinite(north)
    inside = (east >= x0) & (east <= x1) & (north >= y0) & (north <= y1)
    return int((ok & ~inside).sum())


# ──────────────────────────────────────────────────────────────────────────────
# 적재 단계
# ──────────────────────────────────────────────────────────────────────────────


def start_run(conn, source: Source, mode: str, digest: str) -> int:
    with conn.cursor() as cur:
        cur.execute(
            """
            INSERT INTO ingest_run (source, snapshot_date, mode, status, source_hash)
            VALUES (%s, %s, %s, 'RUNNING', %s) RETURNING id
            """,
            (source.code, date.today(), mode, digest),
        )
        return cur.fetchone()[0]


def previous_successful_run(conn, source_code: str, exclude_id: int) -> int | None:
    """직전 성공 회차. 회차 diff 와 COORD_MOVED 규칙의 기준이 된다."""
    with conn.cursor() as cur:
        cur.execute(
            """
            SELECT id FROM ingest_run
            WHERE source = %s AND status = 'SUCCESS' AND id <> %s
            ORDER BY id DESC LIMIT 1
            """,
            (source_code, exclude_id),
        )
        row = cur.fetchone()
        return row[0] if row else None


def unchanged_since_last_run(conn, source_code: str, digest: str) -> bool:
    with conn.cursor() as cur:
        cur.execute(
            """
            SELECT source_hash FROM ingest_run
            WHERE source = %s AND status = 'SUCCESS'
            ORDER BY id DESC LIMIT 1
            """,
            (source_code,),
        )
        row = cur.fetchone()
        return bool(row and row[0] == digest)


def copy_raw(conn, run_id: int, source: Source, limit: int | None) -> int:
    """CSV → poi_raw. COPY 로 청크 단위 벌크 로드."""
    columns = ", ".join(["run_id", *RAW_COLUMNS])
    total = 0
    reader = pd.read_csv(
        source.path,
        encoding=source.encoding,
        dtype=str,
        chunksize=CHUNK_ROWS,
        nrows=limit,
        on_bad_lines="skip",
        low_memory=False,
    )
    with conn.cursor() as cur:
        for chunk in reader:
            if len(chunk.columns) != len(KOREAN_HEADER):
                raise ValueError(
                    f"컬럼 수가 다르다: {len(chunk.columns)} (기대 {len(KOREAN_HEADER)}). "
                    "원본 스키마가 바뀌었는지 확인할 것"
                )
            chunk = chunk.where(pd.notna(chunk), None)
            buf = io.StringIO()
            writer = csv.writer(buf)
            for row in chunk.itertuples(index=False, name=None):
                writer.writerow([run_id, *row])
            buf.seek(0)
            with cur.copy(f"COPY poi_raw ({columns}) FROM STDIN WITH (FORMAT csv)") as cp:
                cp.write(buf.read())
            total += len(chunk)
    return total


def build_staging(conn, run_id: int) -> tuple[int, int]:
    """poi_raw → poi_staging. 타입·좌표 변환과 중복 제거를 여기서 한다.

    `(staging 행 수, 서울 밖 좌표 수)` 를 돌려준다.

    **CHUNK_ROWS 행씩 나눠 처리한다.** 예전에는 raw 한 회차(53만 행)를 통째로 DataFrame 에
    올리고 CSV 전체를 문자열 하나로 만들어 COPY 했다 — 적재 한 번에 1.4GB 를 썼고, 2GB 서버에서
    DB·서버와 같이 돌면 넘친다. 이제 서버 쪽 커서로 끊어 읽어 조각마다 변환해 임시 테이블로 보낸다.

    중복 제거(같은 관리번호는 **파일에서 마지막 것**)는 조각을 넘나들므로 SQL 로 한다 —
    raw 의 `id` 가 파일 순서이고, `DISTINCT ON (source_id) … ORDER BY id DESC` 가 마지막 것이다.
    """
    cols = [
        "id", "mgmt_no", "biz_name", "biz_type_name", "road_address", "jibun_address",
        "phone", "biz_status_name", "license_date", "closed_date", "coord_x", "coord_y",
    ]
    outside = 0
    with conn.cursor() as cur:
        cur.execute("CREATE TEMP TABLE _stg (LIKE poi_staging) ON COMMIT DROP")
        cur.execute("ALTER TABLE _stg DROP COLUMN id, DROP COLUMN geom, DROP COLUMN is_valid")
        cur.execute("ALTER TABLE _stg ADD COLUMN raw_id bigint, "
                    "ADD COLUMN east float8, ADD COLUMN north float8")

        with conn.cursor(name="raw_rows") as src:
            src.itersize = CHUNK_ROWS
            src.execute(f"SELECT {', '.join(cols)} FROM poi_raw WHERE run_id = %s", (run_id,))
            while rows := src.fetchmany(CHUNK_ROWS):
                chunk, n_out = staging_chunk(pd.DataFrame(rows, columns=cols, dtype=object))
                outside += n_out
                buf = io.StringIO()
                writer = csv.writer(buf)
                for r in chunk.itertuples(index=False):
                    writer.writerow(
                        [
                            run_id, r.raw_id, r.source_id, r.name, r.category_raw, r.road_address,
                            r.jibun_address, r.phone, r.biz_status_name,
                            r.licensed_date or "", r.closed_date or "",
                            "" if not np.isfinite(r.east) else r.east,
                            "" if not np.isfinite(r.north) else r.north,
                            "" if not np.isfinite(r.coord_x_raw) else r.coord_x_raw,
                            "" if not np.isfinite(r.coord_y_raw) else r.coord_y_raw,
                        ]
                    )
                with cur.copy(
                    "COPY _stg (run_id, raw_id, source_id, name, category_raw, road_address, "
                    "jibun_address, phone, biz_status_name, licensed_date, closed_date, "
                    "east, north, coord_x_raw, coord_y_raw) FROM STDIN WITH (FORMAT csv)"
                ) as cp:
                    cp.write(buf.getvalue())

        # 같은 관리번호가 여러 건이면 파일에서 마지막 것만 — raw 에는 제약이 없어 중복이 그대로 들어오고,
        # serving 은 UNIQUE(source, source_id) 라 여기서 정리하지 않으면 UPSERT 가 실패한다.
        cur.execute(
            """
            INSERT INTO poi_staging (
                run_id, source_id, name, category_raw, road_address, jibun_address,
                phone, biz_status_name, licensed_date, closed_date, geom,
                coord_x_raw, coord_y_raw)
            SELECT DISTINCT ON (source_id)
                   run_id, source_id, name, category_raw, road_address, jibun_address,
                   phone, biz_status_name, licensed_date, closed_date,
                   CASE WHEN east IS NULL OR north IS NULL THEN NULL
                        ELSE ST_SetSRID(ST_MakePoint(east, north), %s) END,
                   coord_x_raw, coord_y_raw
            FROM _stg
            ORDER BY source_id, raw_id DESC
            """,
            (TARGET_EPSG,),
        )
        inserted = cur.rowcount
        # 방금 넣은 수십만 행을 플래너가 보게 한다.
        # 트랜잭션 안이라 autoanalyze 가 손대지 못하고, 통계가 낡으면 검수 규칙의
        # 조인이 nested loop 로 풀려 몇 분씩 걸린다 (DUPLICATE_NAME_ADDR 에서 실측).
        cur.execute("ANALYZE poi_staging")
    return inserted, outside


def staging_chunk(df: pd.DataFrame) -> tuple[pd.DataFrame, int]:
    """raw 한 조각 → staging 모양. `(조각, 서울 밖 좌표 수)`. 관리번호 없는 행은 버린다."""
    out = pd.DataFrame(
        {
            "raw_id": df["id"],
            "source_id": df["mgmt_no"].str.strip(),
            "name": df["biz_name"].str.strip(),
            "category_raw": df["biz_type_name"].str.strip(),
            "road_address": df["road_address"].str.strip(),
            "jibun_address": df["jibun_address"].str.strip(),
            "phone": df["phone"].str.strip(),
            "biz_status_name": df["biz_status_name"].str.strip(),
            "licensed_date": df["license_date"].map(parse_date),
            "closed_date": df["closed_date"].map(parse_date),
            "coord_x_raw": pd.to_numeric(df["coord_x"].str.strip(), errors="coerce"),
            "coord_y_raw": pd.to_numeric(df["coord_y"].str.strip(), errors="coerce"),
        }
    )
    out = out[out["source_id"].notna() & (out["source_id"] != "")]
    east, north = transform_coords(out["coord_x_raw"].to_numpy(), out["coord_y_raw"].to_numpy())
    out = out.assign(east=east, north=north)
    return out, count_outside_seoul(east, north)


def run_validations(conn, run_id: int, prev_run_id: int | None) -> dict[str, int]:
    """검수 규칙 실행 → validation_result. 규칙별 적중 건수를 돌려준다."""
    counts: dict[str, int] = {}
    with conn.cursor() as cur:
        for rule in FILE_RULES:
            sql = (RULES_DIR / f"{rule}.sql").read_text(encoding="utf-8")
            cur.execute(sql, {"run_id": run_id})
            counts[rule] = cur.rowcount

        if prev_run_id is not None:
            sql = (RULES_DIR / "COORD_MOVED.sql").read_text(encoding="utf-8")
            cur.execute(sql, {"run_id": run_id, "prev_run_id": prev_run_id})
            counts["COORD_MOVED"] = cur.rowcount

        # ERROR 판정을 staging 에 반영 — serving 은 is_valid 인 행만 가져간다
        cur.execute(
            """
            UPDATE poi_staging s SET is_valid = FALSE
            WHERE s.run_id = %(run_id)s AND EXISTS (
                SELECT 1 FROM validation_result v
                WHERE v.run_id = %(run_id)s AND v.severity = 'ERROR'
                  AND v.target_id = s.source_id)
            """,
            {"run_id": run_id},
        )
    return counts


def upsert_categories(conn, run_id: int) -> int:
    with conn.cursor() as cur:
        cur.execute(
            """
            INSERT INTO category_map (category_raw, category_code, display_name)
            SELECT DISTINCT category_raw,
                   replace(replace(category_raw, ' ', ''), '·', ''),
                   category_raw
            FROM poi_staging
            WHERE run_id = %s AND category_raw IS NOT NULL AND category_raw <> ''
            ON CONFLICT (category_raw) DO NOTHING
            """,
            (run_id,),
        )
        return cur.rowcount


def upsert_serving(conn, run_id: int, source_code: str) -> None:
    """검수 통과분만 poi 로 UPSERT. 단일 트랜잭션이므로 실패 시 serving 은 무변경."""
    with conn.cursor() as cur:
        cur.execute(
            """
            INSERT INTO poi (
                source, source_id, name, category_code, category_raw,
                road_address, jibun_address, phone, geom, status,
                licensed_date, closed_date,
                first_seen_run_id, last_seen_run_id, validated_run_id)
            SELECT %(source)s, s.source_id, s.name,
                   replace(replace(s.category_raw, ' ', ''), '·', ''), s.category_raw,
                   s.road_address, s.jibun_address, s.phone, s.geom,
                   CASE WHEN s.biz_status_name = ANY(%(active)s) THEN 'ACTIVE' ELSE 'CLOSED' END,
                   s.licensed_date, s.closed_date,
                   %(run_id)s, %(run_id)s, %(run_id)s
            FROM poi_staging s
            WHERE s.run_id = %(run_id)s AND s.is_valid AND s.geom IS NOT NULL
            ON CONFLICT (source, source_id) DO UPDATE SET
                -- POI 본체는 최신 원본으로 갱신하고, first_seen 은 보존한다
                name             = EXCLUDED.name,
                category_code    = EXCLUDED.category_code,
                category_raw     = EXCLUDED.category_raw,
                road_address     = EXCLUDED.road_address,
                jibun_address    = EXCLUDED.jibun_address,
                phone            = EXCLUDED.phone,
                geom             = EXCLUDED.geom,
                status           = EXCLUDED.status,
                licensed_date    = EXCLUDED.licensed_date,
                closed_date      = EXCLUDED.closed_date,
                last_seen_run_id = EXCLUDED.last_seen_run_id,
                validated_run_id = EXCLUDED.validated_run_id,
                updated_at       = now()
            """,
            {
                "source": source_code,
                "run_id": run_id,
                "active": list(ACTIVE_STATUS_NAMES),
            },
        )


def diff_against_previous(conn, run_id: int, prev_run_id: int | None) -> dict[str, int]:
    """직전 회차와 비교해 신규/유지/소멸/폐업 전환을 센다.

    `ingest_run` 의 건수 컬럼은 **여기서만** 채워진다 — 영업상태 컬럼만 읽어서는 알 수 없다.
    소멸은 '원본 파일에서 레코드가 사라진 것'이며 폐업과는 다른 사건이다.

    **폐업 전환**은 두 회차에 다 있고 직전엔 영업, 이번엔 폐업인 레코드다. 예전에는 이 칸에
    "사라져서 폐업 처리한 건수"(`mark_vanished_as_closed`)를 넣고 있었다 — 첫 적재만 있을 땐
    둘 다 0 이라 드러나지 않았고, 첫 일일 회차(2026-09-22)에서 poi 의 영업 중이 157 줄었는데
    이 칸이 0 이어서 보였다. 사라진 것은 `vanished` 가 이미 센다.
    """
    if prev_run_id is None:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT count(*) FROM poi_staging WHERE run_id = %s", (run_id,)
            )
            return {"new": cur.fetchone()[0], "kept": 0, "vanished": 0, "closed": 0}

    with conn.cursor() as cur:
        cur.execute(
            """
            SELECT
              count(*) FILTER (WHERE p.source_id IS NULL) AS new,
              count(*) FILTER (WHERE c.source_id IS NOT NULL AND p.source_id IS NOT NULL) AS kept,
              count(*) FILTER (WHERE c.source_id IS NULL) AS vanished,
              count(*) FILTER (WHERE p.active AND NOT c.active) AS closed
            FROM (SELECT source_id, biz_status_name = ANY(%(active)s) AS active
                  FROM poi_staging WHERE run_id = %(cur)s) c
            FULL OUTER JOIN
                 (SELECT source_id, biz_status_name = ANY(%(active)s) AS active
                  FROM poi_staging WHERE run_id = %(prev)s) p
              ON c.source_id = p.source_id
            """,
            {"cur": run_id, "prev": prev_run_id, "active": list(ACTIVE_STATUS_NAMES)},
        )
        new, kept, vanished, closed = cur.fetchone()
    return {"new": new, "kept": kept, "vanished": vanished, "closed": closed}


def mark_vanished_as_closed(conn, source_code: str, run_id: int) -> int:
    """최신 회차에 없는 POI 를 CLOSED 로. 행을 지우지 않는다."""
    with conn.cursor() as cur:
        cur.execute(
            """
            UPDATE poi SET status = 'CLOSED', updated_at = now()
            WHERE source = %s AND status = 'ACTIVE'
              AND (last_seen_run_id IS NULL OR last_seen_run_id < %s)
            """,
            (source_code, run_id),
        )
        return cur.rowcount


def prune_staging(conn, source_code: str, keep_run_ids: list[int]) -> int:
    """오래된 staging 회차를 버린다.

    회차 diff 는 **직전 회차 하나만** 필요한데, 남겨두면 회차마다 수십만 행씩 쌓인다.
    일일 자동 실행을 걸면 열흘이면 500만 행이 된다. staging 은 현재·직전만 남긴다.
    raw 와 검수 기록은 `prune_history` 가 따로 정리한다.
    """
    with conn.cursor() as cur:
        cur.execute(
            """
            DELETE FROM poi_staging s
            USING ingest_run r
            WHERE s.run_id = r.id AND r.source = %s AND s.run_id <> ALL(%s)
            """,
            (source_code, keep_run_ids),
        )
        return cur.rowcount


#: raw 를 남길 회차 수(원천마다). raw 는 가공을 다시 돌리기 위한 원본이라 최근 것만 있으면 된다.
#: 회차 하나가 일반음식점 약 280MB 라, 전부 남기면 매일 갱신에서 한 달 8GB 가 쌓인다.
RAW_KEEP_RUNS = 7

#: 검수 기록을 남길 회차 수(원천마다). 장소 상세의 "걸린 검수 기록"과 관리자 화면이 읽는다.
#: 회차당 7만 행 안팎이라 한 달이면 충분하다.
VALIDATION_KEEP_RUNS = 30


def prune_history(conn, source_code: str,
                  raw_keep: int = RAW_KEEP_RUNS,
                  validation_keep: int = VALIDATION_KEEP_RUNS) -> tuple[int, int]:
    """오래된 raw · 검수 기록을 버린다. `(raw 지운 행, 검수 기록 지운 행)`.

    기준은 **그 기록이 있는 회차** 중 최근 N 개다 — SKIPPED 회차는 raw 도 검수도 없으므로
    회차 번호로 세면 건너뛴 날마다 보관 기간이 줄어든다. 회차 자체(`ingest_run`)는 지우지 않는다.
    """
    with conn.cursor() as cur:
        cur.execute(
            """
            DELETE FROM poi_raw WHERE run_id IN (
                SELECT id FROM ingest_run r
                WHERE r.source = %(src)s AND EXISTS (SELECT 1 FROM poi_raw x WHERE x.run_id = r.id)
                ORDER BY id DESC OFFSET %(keep)s)
            """,
            {"src": source_code, "keep": raw_keep},
        )
        raw = cur.rowcount
        cur.execute(
            """
            DELETE FROM validation_result WHERE run_id IN (
                SELECT id FROM ingest_run r
                WHERE r.source = %(src)s AND EXISTS (SELECT 1 FROM validation_result v WHERE v.run_id = r.id)
                ORDER BY id DESC OFFSET %(keep)s)
            """,
            {"src": source_code, "keep": validation_keep},
        )
        return raw, cur.rowcount


def finish_run(conn, run_id: int, status: str, stats: dict, error: str | None = None) -> None:
    with conn.cursor() as cur:
        cur.execute(
            """
            UPDATE ingest_run SET
                status = %(status)s, finished_at = now(),
                duration_ms = %(duration)s,
                rows_total = %(total)s, rows_valid = %(valid)s, rows_rejected = %(rejected)s,
                rows_new = %(new)s, rows_kept = %(kept)s,
                rows_vanished = %(vanished)s, rows_closed = %(closed)s,
                error_message = %(error)s
            WHERE id = %(id)s
            """,
            {"id": run_id, "status": status, "error": error, **stats},
        )


# ──────────────────────────────────────────────────────────────────────────────
# 오케스트레이션
# ──────────────────────────────────────────────────────────────────────────────


def ingest_source(conn, source: Source, limit: int | None, force: bool) -> dict:
    print(f"\n── {source.label} ({source.code})")
    if not source.path.exists():
        raise FileNotFoundError(f"원본이 없다: {source.path}")

    t0 = time.monotonic()
    digest = file_hash(source.path)
    print(f"   해시 {digest[:16]}…")

    if not force and unchanged_since_last_run(conn, source.code, digest):
        run_id = start_run(conn, source, "FULL", digest)
        finish_run(conn, run_id, "SKIPPED", dict.fromkeys(
            ["duration", "total", "valid", "rejected", "new", "kept", "vanished", "closed"], 0))
        conn.commit()
        print("   직전 회차와 동일 → SKIPPED")
        return {"status": "SKIPPED", "run_id": run_id}

    run_id = start_run(conn, source, "FULL", digest)
    prev_run_id = previous_successful_run(conn, source.code, run_id)
    conn.commit()  # 회차 시작은 먼저 확정해 둔다 — 실패해도 기록이 남아야 한다

    try:
        raw_rows = copy_raw(conn, run_id, source, limit)
        print(f"   raw      {raw_rows:,}행")

        stg_rows, outside = build_staging(conn, run_id)
        print(f"   staging  {stg_rows:,}행 (중복 제거 {raw_rows - stg_rows:,})")
        # 검수 규칙이 아니라 계기판이다. bbox 는 실제 행정경계가 아니라 넉넉한 사각형이라
        # 몇 건이 밖에 있는지는 의미가 없다 — 의미 있는 것은 **자릿수**다.
        # 좌표 변환이 깨지면 몇 건이 아니라 수십만 건이 밖으로 나간다.
        if stg_rows and outside / stg_rows > OUTSIDE_ALARM_RATIO:
            print(f"   ⚠ 서울 경계 밖 좌표 {outside:,}건 "
                  f"({outside / stg_rows * 100:.1f}%) — 좌표 변환을 의심할 것")
        elif outside:
            print(f"   (경계 밖 {outside}건 — 정상 범위)")

        rule_counts = run_validations(conn, run_id, prev_run_id)
        for rule, n in rule_counts.items():
            print(f"     {rule:<22} {n:>8,}")

        upsert_categories(conn, run_id)
        upsert_serving(conn, run_id, source.code)
        vanished_closed = mark_vanished_as_closed(conn, source.code, run_id)
        counts = diff_against_previous(conn, run_id, prev_run_id)

        keep = [run_id] + ([prev_run_id] if prev_run_id else [])
        pruned = prune_staging(conn, source.code, keep)
        if pruned:
            print(f"   staging 정리 {pruned:,}행 (현재·직전 회차만 보존)")
        raw_pruned, val_pruned = prune_history(conn, source.code)
        if raw_pruned or val_pruned:
            print(f"   오래된 기록 정리 — raw {raw_pruned:,}행 (최근 {RAW_KEEP_RUNS}회차 보존) · "
                  f"검수 {val_pruned:,}행 (최근 {VALIDATION_KEEP_RUNS}회차 보존)")

        with conn.cursor() as cur:
            cur.execute(
                "SELECT count(*) FROM poi_staging WHERE run_id = %s AND is_valid", (run_id,)
            )
            valid = cur.fetchone()[0]

        finish_run(conn, run_id, "SUCCESS", {
            "duration": int((time.monotonic() - t0) * 1000),
            "total": raw_rows, "valid": valid, "rejected": stg_rows - valid,
            "new": counts["new"], "kept": counts["kept"],
            "vanished": counts["vanished"], "closed": counts["closed"],
        })
        conn.commit()
        print(f"   → SUCCESS  유효 {valid:,} / 거절 {stg_rows - valid:,} / "
              f"신규 {counts['new']:,} · 유지 {counts['kept']:,} · 소멸 {counts['vanished']:,} · "
              f"폐업 전환 {counts['closed']:,}")
        if vanished_closed:
            print(f"   원본에서 사라져 폐업 처리 {vanished_closed:,}건 (poi 에서 지우지 않는다)")
        return {"status": "SUCCESS", "run_id": run_id}

    except Exception as e:  # noqa: BLE001 — 어떤 실패든 회차에 남기고 serving 은 보존한다
        conn.rollback()
        finish_run(conn, run_id, "FAILED", dict.fromkeys(
            ["duration", "total", "valid", "rejected", "new", "kept", "vanished", "closed"], 0),
            error=str(e)[:2000])
        conn.commit()
        print(f"   → FAILED: {e}", file=sys.stderr)
        raise


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="인허가 CSV 적재")
    parser.add_argument("--source", default="all", choices=[*SOURCES, "all"])
    parser.add_argument("--limit", type=int, default=None, help="읽을 행 수 (시험용)")
    parser.add_argument("--force", action="store_true", help="해시가 같아도 다시 적재")
    parser.add_argument(
        "--wait-db",
        type=float,
        default=0,
        metavar="SEC",
        help="DB 에 닿을 때까지 최대 몇 초 기다릴지. 일일 작업은 절전에서 막 깨어나 "
             "Docker 가 아직 안 올라와 있을 수 있어 run_daily.cmd 가 180 을 준다. 기본 0(바로 포기)",
    )
    parser.add_argument(
        "--snapshot",
        default=None,
        help="원본 파일 경로를 덮어쓴다. 다른 회차 스냅샷을 넣거나 실패 격리를 시험할 때 쓴다. "
             "--source 를 하나로 지정해야 한다.",
    )
    args = parser.parse_args(argv)

    if args.snapshot and args.source == "all":
        parser.error("--snapshot 은 --source 를 하나로 지정해야 한다")

    targets = list(SOURCES.values()) if args.source == "all" else [SOURCES[args.source]]
    if args.snapshot:
        targets = [replace(targets[0], path=Path(args.snapshot))]

    # DB 가 꺼져 있으면 회차를 만들지 않고 물러난다.
    # 매일 도는 작업이라, 도커를 안 켠 날마다 FAILED 회차가 쌓이면
    # /admin/ingest-runs 표가 '파이프라인이 자주 깨진다'처럼 읽힌다.
    # 적재를 시도하다 깨진 것과 아예 시작하지 못한 것은 다른 사건이다.
    ok, attempts, waited, error = wait_for_db(args.wait_db)
    if not ok:
        # 몇 번·몇 초 시도했는지 남긴다. 도커를 안 켠 날(바로 실패)과 깨어나는 게
        # 늦었던 날(기다리다 실패)을 로그만 보고 구분할 수 있어야 한다.
        print(f"DB 에 접속하지 못했다 ({attempts}회 · {waited:.0f}초): {error}", file=sys.stderr)
        print("  docker compose up -d db 로 띄웠는지 확인할 것", file=sys.stderr)
        return EXIT_DB_UNAVAILABLE
    if attempts > 1:
        print(f"DB 접속 {attempts}회째 성공 ({waited:.0f}초 기다림)")

    with connect() as conn:
        for source in targets:
            ingest_source(conn, source, args.limit, args.force)

        with conn.cursor() as cur:
            cur.execute(
                "SELECT status, count(*) FROM poi GROUP BY status ORDER BY status"
            )
            rows = cur.fetchall()
    print("\npoi 합계:", ", ".join(f"{s} {n:,}" for s, n in rows) or "없음")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
