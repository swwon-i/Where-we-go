"""D0 검증 — LOCALDATA 인허가 좌표의 원본 좌표계를 실측으로 판정한다.

계획서 §6 "D0 — 5174 → 5186 변환 검증"의 구현.

배경
----
LOCALDATA 인허가 데이터의 좌표는 "보정계수 미적용 Bessel 중부원점TM"으로 알려져 있으나,
실제로 어느 EPSG인지는 파일에 적혀 있지 않다. 후보가 여럿이고 선택에 따라 결과가
수십~수백 미터 어긋난다.

  - EPSG:5174  Korean 1985 / Modified Central Belt (lon_0 = 127.0028902777778, 보정 적용)
  - EPSG:2097  Korean 1985 / Central Belt          (lon_0 = 127,               보정 미적용)
  - 위 둘의 lon_0 차이는 약 10.4초 ≈ 257m — 잘못 고르면 그만큼 밀린다.
  - 여기에 Bessel → WGS84 datum shift(towgs84) 적용 여부가 겹치면 추가로 300~400m 어긋난다.
  - PROJ 버전에 따라 EPSG:5174에 datum shift가 자동 적용되기도, 안 되기도 한다.

문제는 **전체가 같은 방향으로 밀리면 지도에서 눈으로는 잡히지 않는다**는 점이다.
그래서 좌표를 아는 지점(앵커)과 대조해 후보별 오차를 실측한다.

판정
----
후보별로 앵커까지의 중앙 오차를 재고, 가장 작은 후보를 원본 좌표계로 채택한다.
  PASS  < 150m   채택 가능
  WARN  150~300m 의심 — 앵커를 늘려 재확인
  FAIL  > 300m   해당 후보 아님

음성 대조군(EPSG:5186을 원본으로 잘못 가정)을 함께 돌려 검사 자체가 판별력을 갖는지 확인한다.

사용법
-----
    python -m etl.verify_coords --csv "csv/장소/식품_일반음식점_서울특별시.csv"
    python -m etl.verify_coords --csv ... --nrows 200000 --out etl/out/coord_verification.json
"""

from __future__ import annotations

import argparse
import json
import math
import sys
from dataclasses import dataclass, asdict
from pathlib import Path

import pandas as pd
from pyproj import CRS, Transformer

# --------------------------------------------------------------------------------------
# 상수
# --------------------------------------------------------------------------------------

#: 인허가 CSV 인코딩. UTF-8 아님 — 계획서 §3 참조.
CSV_ENCODING = "cp949"

COL_NAME = "사업장명"
COL_JIBUN = "지번주소"
COL_X = "좌표정보(X)"
COL_Y = "좌표정보(Y)"

#: 서울 대략 경계 (경도 min, 위도 min, 경도 max, 위도 max). 1차 관문용이며 정밀 검수는 행정동 경계로 한다.
SEOUL_BBOX = (126.73, 37.40, 127.27, 37.72)

#: 판정 임계값 (미터)
THRESHOLD_PASS = 150.0
THRESHOLD_WARN = 300.0

_BESSEL_MODIFIED = (
    "+proj=tmerc +lat_0=38 +lon_0=127.0028902777778 +k=1 "
    "+x_0=200000 +y_0=500000 +ellps=bessel +units=m +no_defs"
)
_BESSEL_PLAIN = (
    "+proj=tmerc +lat_0=38 +lon_0=127 +k=1 "
    "+x_0=200000 +y_0=500000 +ellps=bessel +units=m +no_defs"
)

#: 검증할 원본 좌표계 후보. label -> PROJ 정의
#:
#: towgs84 파라미터는 국내에서 통용되는 두 조합이다. 어느 쪽이 맞는지는 데이터가 정한다 —
#: 그래서 추정하지 않고 전부 돌려본다.
CRS_CANDIDATES: dict[str, str] = {
    "EPSG:5174 (PROJ 기본값)": "EPSG:5174",
    "EPSG:2097 (보정 미적용)": "EPSG:2097",
    "Bessel 보정 + towgs84 7p": f"{_BESSEL_MODIFIED} +towgs84=-145.907,505.034,685.756,-1.162,2.347,1.592,6.342",
    "Bessel 보정 + towgs84 3p": f"{_BESSEL_MODIFIED} +towgs84=-146.43,507.89,681.46",
    "Bessel 무보정 + towgs84 7p": f"{_BESSEL_PLAIN} +towgs84=-145.907,505.034,685.756,-1.162,2.347,1.592,6.342",
    "[음성대조군] EPSG:5186": "EPSG:5186",
}


@dataclass(frozen=True)
class Anchor:
    """좌표를 아는 기준점.

    `jibun`은 지번주소에 대해 정규식으로 매칭한다. 번지 뒤에 숫자가 이어지는 경우를
    걸러야 하므로(`신천동 29`가 `신천동 293`을 잡으면 안 된다) 호출부에서 경계를 붙인다.

    lat/lon은 해당 지번 필지의 대략적인 중심이다. 손으로 넣은 값이며, 건물 복합체는
    필지가 넓어 ±100m 수준의 자체 오차가 있다. 판정 임계값은 이를 감안해 잡았다.
    """

    label: str
    jibun: str
    lat: float
    lon: float


#: 기준점 — 넓은 필지에 업소가 다수 입주해 표본이 충분히 나오는 곳으로 골랐다.
#: 좌표는 손으로 입력한 참조값이므로, 의심되면 이 표부터 확인할 것.
ANCHORS: list[Anchor] = [
    Anchor("롯데월드타워·몰 (송파구 신천동 29)", r"송파구 신천동 29", 37.5130, 127.1027),
    Anchor("코엑스 (강남구 삼성동 159)", r"강남구 삼성동 159", 37.5115, 127.0595),
    Anchor("서울역 (중구 봉래동2가 122)", r"중구 봉래동2가 122", 37.5547, 126.9707),
    Anchor("고속터미널 (서초구 반포동 19-4)", r"서초구 반포동 19-4", 37.5049, 127.0048),
    Anchor("63빌딩 (영등포구 여의도동 60)", r"영등포구 여의도동 60", 37.5197, 126.9400),
    Anchor("IFC (영등포구 여의도동 23)", r"영등포구 여의도동 23", 37.5250, 126.9255),
    Anchor("서울시청 (중구 태평로1가 31)", r"중구 태평로1가 31", 37.5663, 126.9779),
    Anchor("잠실종합운동장 (송파구 잠실동 10)", r"송파구 잠실동 10", 37.5152, 127.0731),
]


# --------------------------------------------------------------------------------------
# 순수 함수 — 테스트 대상
# --------------------------------------------------------------------------------------


def haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """두 WGS84 좌표 사이의 대권 거리(미터)."""
    radius = 6_371_008.8  # 평균 지구 반지름
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = p2 - p1
    dl = math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * radius * math.asin(math.sqrt(a))


def in_seoul(lon: float, lat: float) -> bool:
    """서울 대략 경계 안에 드는지. 좌표계를 완전히 잘못 고른 경우를 걸러내는 1차 관문."""
    min_lon, min_lat, max_lon, max_lat = SEOUL_BBOX
    return min_lon <= lon <= max_lon and min_lat <= lat <= max_lat


def verdict(median_error_m: float | None) -> str:
    """중앙 오차로 후보를 판정한다."""
    if median_error_m is None:
        return "NO_DATA"
    if median_error_m < THRESHOLD_PASS:
        return "PASS"
    if median_error_m < THRESHOLD_WARN:
        return "WARN"
    return "FAIL"


def make_transformer(crs_def: str) -> Transformer:
    """원본 좌표계 → WGS84 변환기. `always_xy=True`이므로 입출력 순서는 (동, 북) / (경도, 위도)."""
    return Transformer.from_crs(CRS.from_user_input(crs_def), CRS.from_epsg(4326), always_xy=True)


def load_coords(csv_path: str | Path, nrows: int | None = None) -> pd.DataFrame:
    """인허가 CSV에서 좌표가 있는 행만 뽑아 `name / jibun / x / y` 로 정규화한다.

    좌표 컬럼에는 뒤쪽 공백이 붙어 있고 결측이 많다 — 둘 다 여기서 처리한다.
    """
    df = pd.read_csv(
        csv_path,
        encoding=CSV_ENCODING,
        usecols=[COL_NAME, COL_JIBUN, COL_X, COL_Y],
        dtype=str,
        nrows=nrows,
        on_bad_lines="skip",
        low_memory=False,
    )
    df = df.rename(
        columns={COL_NAME: "name", COL_JIBUN: "jibun", COL_X: "x", COL_Y: "y"}
    )
    df["x"] = pd.to_numeric(df["x"].str.strip(), errors="coerce")
    df["y"] = pd.to_numeric(df["y"].str.strip(), errors="coerce")
    df["jibun"] = df["jibun"].fillna("").str.strip()
    return df.dropna(subset=["x", "y"]).reset_index(drop=True)


def match_anchor(df: pd.DataFrame, anchor: Anchor) -> pd.DataFrame:
    """앵커 지번에 해당하는 업소를 고른다.

    번지 뒤에 숫자가 이어지면 다른 필지이므로 제외한다 (`신천동 29` != `신천동 293`).
    """
    pattern = anchor.jibun + r"(?!\d)"
    return df[df["jibun"].str.contains(pattern, regex=True, na=False)]


def evaluate_candidate(
    df: pd.DataFrame, label: str, crs_def: str, anchors: list[Anchor]
) -> dict:
    """후보 좌표계 하나를 앵커 전체에 대해 평가한다."""
    transformer = make_transformer(crs_def)

    lon, lat = transformer.transform(df["x"].to_numpy(), df["y"].to_numpy())
    in_bbox = [in_seoul(lo, la) for lo, la in zip(lon, lat)]
    bbox_ratio = sum(in_bbox) / len(in_bbox) if len(in_bbox) else 0.0

    per_anchor: list[dict] = []
    all_errors: list[float] = []

    for anchor in anchors:
        hits = match_anchor(df, anchor)
        if hits.empty:
            per_anchor.append(
                {"anchor": anchor.label, "samples": 0, "median_error_m": None}
            )
            continue

        a_lon, a_lat = transformer.transform(hits["x"].to_numpy(), hits["y"].to_numpy())
        errors = [
            haversine_m(anchor.lat, anchor.lon, la, lo) for lo, la in zip(a_lon, a_lat)
        ]
        median = float(pd.Series(errors).median())
        all_errors.extend(errors)
        per_anchor.append(
            {
                "anchor": anchor.label,
                "samples": len(errors),
                "median_error_m": round(median, 1),
            }
        )

    overall = float(pd.Series(all_errors).median()) if all_errors else None
    return {
        "label": label,
        "crs": crs_def,
        "bbox_ratio": round(bbox_ratio, 4),
        "median_error_m": round(overall, 1) if overall is not None else None,
        "sample_count": len(all_errors),
        "verdict": verdict(overall),
        "per_anchor": per_anchor,
    }


def pick_best(results: list[dict]) -> dict | None:
    """오차가 가장 작은 후보. 음성 대조군은 제외한다."""
    scored = [
        r
        for r in results
        if r["median_error_m"] is not None and not r["label"].startswith("[음성대조군]")
    ]
    return min(scored, key=lambda r: r["median_error_m"]) if scored else None


def pairwise_spread(
    df: pd.DataFrame, labels: list[str], sample_n: int = 5_000, seed: int = 0
) -> list[dict]:
    """통과 후보끼리 결과가 실제로 얼마나 다른지 잰다.

    앵커 대조는 "어느 후보가 맞는가"에 답하지만, 앵커 좌표 자체에 오차가 있어
    비슷한 후보들을 갈라내지 못한다. 이 함수는 다른 질문에 답한다 —
    **선택이 결과를 바꾸기는 하는가.** 이격이 앵커 오차보다 작다면 어느 쪽을 고르든
    무방하므로, 더 파고들 필요 없이 하나를 정하고 넘어가면 된다.
    """
    sample = df.sample(n=min(sample_n, len(df)), random_state=seed)
    xs, ys = sample["x"].to_numpy(), sample["y"].to_numpy()

    projected = {}
    for label in labels:
        lon, lat = make_transformer(CRS_CANDIDATES[label]).transform(xs, ys)
        projected[label] = (lon, lat)

    out: list[dict] = []
    for i, a in enumerate(labels):
        for b in labels[i + 1 :]:
            lon_a, lat_a = projected[a]
            lon_b, lat_b = projected[b]
            d = [
                haversine_m(la, lo, lb, ob)
                for lo, la, ob, lb in zip(lon_a, lat_a, lon_b, lat_b)
            ]
            s = pd.Series(d)
            out.append(
                {
                    "pair": f"{a}  ↔  {b}",
                    "median_m": round(float(s.median()), 1),
                    "max_m": round(float(s.max()), 1),
                }
            )
    return out


# --------------------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------------------


def _print_spread(spread: list[dict]) -> None:
    if not spread:
        return
    print("\n통과 후보 간 이격 (선택이 결과를 바꾸는가):")
    for s in spread:
        print(f"  - {s['pair']:<52} 중앙 {s['median_m']:>7,.1f}m  최대 {s['max_m']:>7,.1f}m")

    worst = max(s["median_m"] for s in spread)
    if worst < THRESHOLD_PASS:
        print(
            f"\n  → 이격 최대 {worst:,.1f}m로 앵커 오차보다 작다. 통과 후보 중 무엇을 고르든"
            "\n    결과가 사실상 같으므로 더 가릴 필요 없다."
            "\n    다만 EPSG:5174의 datum shift 적용 여부는 PROJ 버전에 따라 달라지고,"
            "\n    pyproj와 PostGIS가 서로 다른 정의를 쓸 수 있다. 재현성을 위해"
            "\n    **proj4 문자열을 명시 고정**하고 ETL과 DB 양쪽에 같은 값을 쓸 것."
        )
    else:
        print(
            f"\n  → 이격이 최대 {worst:,.1f}m다. 선택이 결과를 바꾸므로 앵커를 늘려 어느 쪽인지 가릴 것."
        )


def _print_report(results: list[dict], best: dict | None, total_rows: int) -> None:
    print(f"\n좌표 보유 행: {total_rows:,}건\n")
    print(f"{'후보 좌표계':<28} {'서울 bbox':>9} {'표본':>6} {'중앙오차':>10}  판정")
    print("-" * 72)
    for r in results:
        err = f"{r['median_error_m']:,.1f}m" if r["median_error_m"] is not None else "-"
        print(
            f"{r['label']:<28} {r['bbox_ratio']*100:>8.1f}% "
            f"{r['sample_count']:>6,} {err:>10}  {r['verdict']}"
        )

    if best is None:
        print("\n앵커에 매칭된 업소가 없다. ANCHORS의 지번 표기를 데이터와 대조할 것.")
        return

    print(f"\n채택: {best['label']}  (중앙오차 {best['median_error_m']:,.1f}m)")
    print(f"      {best['crs']}\n")
    print("앵커별 오차:")
    for a in best["per_anchor"]:
        err = f"{a['median_error_m']:,.1f}m" if a["median_error_m"] is not None else "매칭 없음"
        print(f"  - {a['anchor']:<36} {a['samples']:>5,}건  {err}")

    if best["verdict"] != "PASS":
        print(
            f"\n[주의] 최선 후보도 {best['verdict']}다. ANCHORS 좌표가 손으로 넣은 참조값이므로"
            "\n       먼저 그 표를 의심하고, 그래도 크면 다른 towgs84 조합을 추가할 것."
        )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="LOCALDATA 좌표계 판정 (D0)")
    parser.add_argument(
        "--csv",
        default="csv/장소/식품_일반음식점_서울특별시.csv",
        help="인허가 CSV 경로",
    )
    parser.add_argument(
        "--nrows", type=int, default=None, help="읽을 행 수 (기본: 전체)"
    )
    parser.add_argument("--out", default=None, help="JSON 리포트 저장 경로")
    args = parser.parse_args(argv)

    csv_path = Path(args.csv)
    if not csv_path.exists():
        print(f"파일 없음: {csv_path}", file=sys.stderr)
        return 2

    df = load_coords(csv_path, nrows=args.nrows)
    if df.empty:
        print("좌표를 가진 행이 없다.", file=sys.stderr)
        return 2

    results = [
        evaluate_candidate(df, label, crs_def, ANCHORS)
        for label, crs_def in CRS_CANDIDATES.items()
    ]
    best = pick_best(results)
    _print_report(results, best, len(df))

    passing = [
        r["label"]
        for r in results
        if r["verdict"] == "PASS" and not r["label"].startswith("[음성대조군]")
    ]
    spread = pairwise_spread(df, passing) if len(passing) > 1 else []
    _print_spread(spread)

    if args.out:
        out_path = Path(args.out)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        payload = {
            "csv": str(csv_path),
            "rows_with_coords": len(df),
            "anchors": [asdict(a) for a in ANCHORS],
            "candidates": results,
            "pairwise_spread": spread,
            "adopted": best,
        }
        out_path.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        print(f"\n리포트 저장: {out_path}")

    return 0 if best and best["verdict"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
