"""ETL(pyproj)과 DB(PostGIS)가 같은 좌표를 내는지 확인한다.

왜 필요한가
----------
좌표 변환을 ETL에서만 수행하고 DB는 저장만 하기로 했다(V1 마이그레이션 주석 참조).
그래도 두 엔진이 **같은 EPSG 코드를 다르게 해석하지 않는지**는 확인해야 한다.
EPSG:5174의 datum shift 적용 여부는 PROJ 버전과 grid 가용성에 따라 달라지고,
pyproj와 PostGIS가 서로 다른 PROJ를 링크할 수 있기 때문이다.

어긋나면 ETL이 넣은 좌표와 DB가 `ST_Transform`으로 계산한 좌표가 달라지고,
반경 검색·스냅 거리가 조용히 틀어진다. 눈으로는 잡히지 않는 종류의 오류다.

DB 셋업 직후와 PROJ·PostGIS 버전을 올릴 때마다 돌린다.

사용법
-----
    python -m etl.verify_db_crs
    python -m etl.verify_db_crs --samples 500 --tolerance 0.01
"""

from __future__ import annotations

import argparse
import os
import sys

import numpy as np
from pyproj import CRS, Transformer

#: 기본 접속 정보 — docker-compose.yml 의 값과 같다.
DEFAULT_DSN = os.environ.get(
    "WWG_DSN", "postgresql://wherewego:wherewego@localhost:5432/wherewego"
)

SRC_EPSG = 5174  # 인허가 원본 (Bessel 중부원점TM, 보정 적용)
DST_EPSG = 5186  # 저장 좌표계 (Korea 2000 중부원점)

#: 허용 오차(미터). 두 엔진이 같은 정의를 쓰면 부동소수점 수준(<1mm)으로 일치해야 한다.
#: 이보다 크면 정의가 다르다는 뜻이므로, 값을 느슨하게 풀지 말고 원인을 찾을 것.
DEFAULT_TOLERANCE_M = 0.001

#: 서울 인허가 좌표가 실제로 분포하는 5174 범위. 표본은 여기서 뽑는다.
SEOUL_5174_BOUNDS = (180_000.0, 435_000.0, 215_000.0, 465_000.0)


def sample_points(n: int, seed: int = 0) -> np.ndarray:
    """서울 범위의 5174 좌표를 n개 만든다. `(n, 2)` 배열."""
    x0, y0, x1, y1 = SEOUL_5174_BOUNDS
    rng = np.random.default_rng(seed)
    return np.column_stack([rng.uniform(x0, x1, n), rng.uniform(y0, y1, n)])


def transform_with_pyproj(pts: np.ndarray) -> np.ndarray:
    """pyproj로 5174 → 5186."""
    t = Transformer.from_crs(
        CRS.from_epsg(SRC_EPSG), CRS.from_epsg(DST_EPSG), always_xy=True
    )
    east, north = t.transform(pts[:, 0], pts[:, 1])
    return np.column_stack([east, north])


def transform_with_postgis(conn, pts: np.ndarray) -> np.ndarray:
    """PostGIS로 5174 → 5186. 입력 순서를 그대로 유지해 돌려준다."""
    rows = [(float(x), float(y)) for x, y in pts]
    with conn.cursor() as cur:
        cur.execute(
            """
            SELECT ST_X(g), ST_Y(g)
            FROM unnest(%s::float8[], %s::float8[]) WITH ORDINALITY AS u(x, y, ord)
            CROSS JOIN LATERAL (
                SELECT ST_Transform(ST_SetSRID(ST_MakePoint(u.x, u.y), %s), %s) AS g
            ) t
            ORDER BY u.ord
            """,
            ([r[0] for r in rows], [r[1] for r in rows], SRC_EPSG, DST_EPSG),
        )
        return np.array(cur.fetchall(), dtype=float)


def compare(a: np.ndarray, b: np.ndarray) -> dict:
    """두 좌표 집합의 차이를 미터로 요약한다."""
    d = np.hypot(a[:, 0] - b[:, 0], a[:, 1] - b[:, 1])
    return {
        "count": int(len(d)),
        "max_m": float(d.max()),
        "median_m": float(np.median(d)),
        "mean_m": float(d.mean()),
    }


def verdict(stats: dict, tolerance_m: float) -> bool:
    """최대 오차가 허용치 안이면 통과."""
    return stats["max_m"] <= tolerance_m


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="pyproj ↔ PostGIS 좌표 변환 일치 확인")
    parser.add_argument("--dsn", default=DEFAULT_DSN)
    parser.add_argument("--samples", type=int, default=1_000)
    parser.add_argument("--seed", type=int, default=0)
    parser.add_argument("--tolerance", type=float, default=DEFAULT_TOLERANCE_M)
    args = parser.parse_args(argv)

    try:
        import psycopg
    except ImportError:
        print("psycopg 가 없다. pip install -r etl/requirements.txt", file=sys.stderr)
        return 2

    pts = sample_points(args.samples, args.seed)
    mine = transform_with_pyproj(pts)

    try:
        with psycopg.connect(args.dsn, connect_timeout=10) as conn:
            with conn.cursor() as cur:
                cur.execute("SELECT postgis_version(), version()")
                pg_version, pg_full = cur.fetchone()
            theirs = transform_with_postgis(conn, pts)
    except Exception as e:  # noqa: BLE001 — 접속 실패 원인을 그대로 보여준다
        print(f"DB 접속 실패: {e}", file=sys.stderr)
        print("  docker compose up -d db 로 띄웠는지 확인할 것", file=sys.stderr)
        return 2

    from pyproj import proj_version_str

    stats = compare(mine, theirs)
    ok = verdict(stats, args.tolerance)

    print(f"\nEPSG:{SRC_EPSG} → EPSG:{DST_EPSG}  표본 {stats['count']:,}점\n")
    print(f"  ETL : pyproj / PROJ {proj_version_str}")
    print(f"  DB  : PostGIS {pg_version.split()[0]} / {pg_full.split(',')[0]}")
    print()
    print(f"  최대 차이   {stats['max_m']:.6f} m")
    print(f"  중앙 차이   {stats['median_m']:.6f} m")
    print(f"  허용치     {args.tolerance} m")
    print(f"\n  {'일치 — 두 엔진이 같은 정의를 쓴다' if ok else '불일치 — 정의가 다르다'}")

    if not ok:
        print(
            "\n  허용치를 늘려 넘기지 말 것. 한쪽의 PROJ 버전 또는 proj4 정의가 다른 것이며,"
            "\n  방치하면 반경 검색과 스냅 거리가 조용히 틀어진다.",
            file=sys.stderr,
        )
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
