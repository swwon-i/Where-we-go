"""D0 보강 — 서로 다른 좌표계의 두 데이터가 5186에서 겹치는지 교차 검증한다.

배경
----
`verify_coords.py`는 손으로 넣은 앵커 8곳과 대조해 POI의 원본 좌표계를 판정했다.
그 방법의 약점은 **기준값을 사람이 입력한다**는 것이다 — 잔여 오차 ~124m의 대부분이
앵커 좌표 자체의 오차였다.

여기서는 기준값을 사람이 만들지 않는다. 서울 버스 정류장 11,494개(위경도, EPSG:4326)를
기준으로 쓴다. 서울 시가지에서 음식점은 거의 예외 없이 정류장 가까이에 있으므로,
**POI 좌표계를 옳게 고르면 "가장 가까운 정류장까지의 거리"가 작아지고, 틀리게 고르면
그 분포가 통째로 밀린다.**

즉 이 검사는 두 가지를 한 번에 확인한다.
  1. POI(5174 계열)와 정류장(4326)을 5186으로 통일했을 때 실제로 정합하는가
  2. `verify_coords.py`의 판정이 독립적인 데이터로도 재현되는가

사용법
-----
    python -m etl.verify_crs_alignment
    python -m etl.verify_crs_alignment --sample 20000 --out etl/out/crs_alignment.json
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd
from pyproj import CRS, Transformer

from etl.verify_coords import CRS_CANDIDATES, CSV_ENCODING, load_coords

#: 저장·연산 좌표계. 미터 단위이므로 거리 계산에 그대로 쓴다.
TARGET_CRS = "EPSG:5186"

#: 정류장마스터 (EPSG:4326)
STOP_CSV = "csv/버스/서울시 정류장마스터 정보.csv"
STOP_LAT, STOP_LON = "위도", "경도"

#: 서울 시가지에서 "가장 가까운 버스 정류장까지"의 중앙 거리가 이보다 크면 정합이 깨진 것으로 본다.
ALIGNED_MEDIAN_M = 250.0


def load_stops(csv_path: str | Path) -> pd.DataFrame:
    """정류장마스터를 읽어 5186 좌표로 변환한다. 원본은 위경도(4326)다."""
    df = pd.read_csv(csv_path, encoding=CSV_ENCODING, dtype=str)
    df[STOP_LAT] = pd.to_numeric(df[STOP_LAT], errors="coerce")
    df[STOP_LON] = pd.to_numeric(df[STOP_LON], errors="coerce")
    df = df.dropna(subset=[STOP_LAT, STOP_LON])

    to_target = Transformer.from_crs(
        CRS.from_epsg(4326), CRS.from_user_input(TARGET_CRS), always_xy=True
    )
    east, north = to_target.transform(df[STOP_LON].to_numpy(), df[STOP_LAT].to_numpy())
    return pd.DataFrame({"east": east, "north": north})


def nearest_distances(
    px: np.ndarray, py: np.ndarray, qx: np.ndarray, qy: np.ndarray, chunk: int = 2_000
) -> np.ndarray:
    """각 p에서 가장 가까운 q까지의 거리(미터). 좌표가 이미 미터 단위(5186)라 평면 거리로 충분하다."""
    out = np.empty(len(px))
    for i in range(0, len(px), chunk):
        dx = px[i : i + chunk, None] - qx[None, :]
        dy = py[i : i + chunk, None] - qy[None, :]
        out[i : i + chunk] = np.sqrt(np.min(dx * dx + dy * dy, axis=1))
    return out


def evaluate_alignment(
    poi: pd.DataFrame, stops: pd.DataFrame, label: str, crs_def: str
) -> dict:
    """POI를 `crs_def`로 해석해 5186으로 옮긴 뒤, 정류장과의 정합을 측정한다."""
    to_target = Transformer.from_crs(
        CRS.from_user_input(crs_def), CRS.from_user_input(TARGET_CRS), always_xy=True
    )
    east, north = to_target.transform(poi["x"].to_numpy(), poi["y"].to_numpy())

    d = nearest_distances(
        east, north, stops["east"].to_numpy(), stops["north"].to_numpy()
    )
    s = pd.Series(d)
    median = float(s.median())
    return {
        "label": label,
        "crs": crs_def,
        "median_m": round(median, 1),
        "p25_m": round(float(s.quantile(0.25)), 1),
        "p75_m": round(float(s.quantile(0.75)), 1),
        "p95_m": round(float(s.quantile(0.95)), 1),
        "within_100m_ratio": round(float((s < 100).mean()), 4),
        "aligned": median < ALIGNED_MEDIAN_M,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="POI↔정류장 좌표계 정합 교차 검증")
    parser.add_argument("--poi-csv", default="csv/장소/식품_일반음식점_서울특별시.csv")
    parser.add_argument("--stop-csv", default=STOP_CSV)
    parser.add_argument("--sample", type=int, default=20_000, help="POI 표본 수")
    parser.add_argument("--seed", type=int, default=0)
    parser.add_argument("--out", default=None)
    args = parser.parse_args(argv)

    for p in (args.poi_csv, args.stop_csv):
        if not Path(p).exists():
            print(f"파일 없음: {p}", file=sys.stderr)
            return 2

    stops = load_stops(args.stop_csv)
    poi = load_coords(args.poi_csv)
    poi = poi.sample(n=min(args.sample, len(poi)), random_state=args.seed)

    print(f"\n정류장 {len(stops):,}개 (원본 4326 → {TARGET_CRS})")
    print(f"POI 표본 {len(poi):,}건 (원본 5174 계열 → {TARGET_CRS})\n")

    results = [
        evaluate_alignment(poi, stops, label, crs_def)
        for label, crs_def in CRS_CANDIDATES.items()
    ]
    results.sort(key=lambda r: r["median_m"])

    print("가장 가까운 정류장까지의 거리 — POI 좌표계 해석별")
    print(f"{'후보 좌표계':<28} {'중앙':>8} {'p75':>8} {'p95':>9} {'<100m':>7}  정합")
    print("-" * 72)
    for r in results:
        print(
            f"{r['label']:<28} {r['median_m']:>7,.1f}m {r['p75_m']:>7,.1f}m "
            f"{r['p95_m']:>8,.1f}m {r['within_100m_ratio']*100:>6.1f}%  "
            f"{'OK' if r['aligned'] else 'X'}"
        )

    best, worst = results[0], results[-1]
    print(f"\n최소: {best['label']}  중앙 {best['median_m']:,.1f}m")
    print(f"최대: {worst['label']}  중앙 {worst['median_m']:,.1f}m")
    print(
        "\n  → 좌표계를 잘못 고르면 분포가 통째로 밀린다. 이 표가 벌어질수록 검사에 판별력이 있다는 뜻이고,"
        "\n    최소 후보의 중앙값이 작다는 것은 POI(5174)와 정류장(4326)이 5186에서 실제로 정합한다는 뜻이다."
    )

    if args.out:
        out_path = Path(args.out)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(
            json.dumps(
                {
                    "target_crs": TARGET_CRS,
                    "stop_count": len(stops),
                    "poi_sample": len(poi),
                    "candidates": results,
                },
                ensure_ascii=False,
                indent=2,
            ),
            encoding="utf-8",
        )
        print(f"\n리포트 저장: {out_path}")

    return 0 if best["aligned"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
