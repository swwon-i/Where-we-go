"""지하철 시각표에서 역간 소요시간과 시간대별 배차를 산출한다.

    서울교통공사_도시철도열차운행시각표.csv  (532,832행 / 1~9호선)
        │
        ├─▶ 역간 소요시간   (LINE, INOUTTAG, TRAIN_NO) 로 묶어 연속 역의 시각 차
        └─▶ 시간대별 배차   (SI_ID, INOUTTAG) 로 묶어 연속 열차의 도착 시각 차

2주차 그래프 빌드의 입력이다. 기존 「역간거리 및 소요시간」 파일은 1~8호선 표준값인 반면
이쪽은 1~9호선 실제 시각표라 커버리지가 넓고, 시간대별 배차까지 같은 파일에서 나온다.

핵심은 `TRAIN_NO`(열차번호)다. 이것이 없으면 "같은 열차가 연속한 두 역에 도착한 시각"을
묶을 수 없어 역간 소요시간을 산출할 수 없다.

사용법
-----
    python -m etl.subway_timing
    python -m etl.subway_timing --weektag SAT --out etl/out/subway
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

#: 이 파일만 UTF-8 BOM 이다. 다른 원본은 전부 CP949 (계획서 §3).
TIMETABLE_ENCODING = "utf-8-sig"
TIMETABLE_PATH = "csv/지하철/서울교통공사_도시철도열차운행시각표(250930).csv"
STATION_MASTER_PATH = "csv/지하철/서울시 역사마스터 정보.csv"

#: 주중(DAY) / 토요일(SAT) / 일요일·공휴일(END)
WEEKTAGS = ("DAY", "SAT", "END")

#: 역간 주행시간으로 인정할 범위(초). 이 밖은 회차·주박 등 운행 외 구간으로 보고 버린다.
RUN_MIN_SEC, RUN_MAX_SEC = 1, 1_800
#: 배차로 인정할 범위(초). 1시간을 넘으면 운행 종료 구간이다.
HEADWAY_MIN_SEC, HEADWAY_MAX_SEC = 1, 3_600

#: 역사마스터는 1호선 바깥 구간을 경부선·경인선 등 별도 노선명으로 담는다.
#: 매핑 없이 조인하면 70.6%, 있으면 98.0% 가 붙는다.
LINE_ALIAS: dict[str, str] = {
    "1호선": "1", "경부선": "1", "경인선": "1", "경원선": "1", "장항선": "1",
    "2호선": "2",
    "3호선": "3", "일산선": "3",
    "4호선": "4", "안산선": "4", "과천선": "4", "진접선": "4",
    "5호선": "5",
    "6호선": "6",
    "7호선": "7", "7호선(인천)": "7",
    "8호선": "8", "별내선": "8",
    "9호선": "9", "9호선(연장)": "9",
}

#: 알리아스로도 안 붙는 역. 개명·표기 차이라 손으로 맨다.
#: (시각표 역명) -> (역사마스터 역사명)
STATION_ALIAS: dict[tuple[str, str], str] = {
    ("4", "당고개"): "불암산",     # 2024 진접선 연장으로 개명
    ("6", "신내역"): "신내",       # 역명에 '역'이 붙은 표기
}

#: 대조용 참조값 — 서울교통공사 「열차운행현황」(2024-03-31)의 호선별 운행 시격(분).
#: 13행짜리 요약표이며 RH(출근)/NH(평시) 두 값만 준다. 산출값의 자릿수가 맞는지
#: 확인하는 용도이고, 실제 배차는 시각표에서 뽑은 시간대별 값을 쓴다.
REFERENCE_HEADWAY_MIN = {
    "1": (3.0, 5.0), "2": (2.5, 5.5), "3": (3.0, 6.5), "4": (2.5, 5.5),
    "5": (2.5, 6.5), "6": (4.0, 8.0), "7": (2.5, 6.0), "8": (4.5, 8.5),
}

#: 참조값이 재는 **범위**. 운행현황은 서울교통공사가 직접 운영하는 구간만 담는다
#: (1호선은 서울역~청량리 10역). 반면 시각표는 코레일 직결 전체(양주~인천)를 담는다.
#: 범위를 맞추지 않고 비교하면 멀쩡한 산출값이 1호선 2.67배, 4호선 2.4배로 튄다 — 실측:
#:   1호선  공사 구간 3.5분 / 그 밖 8.0분      4호선  공사 구간 3.0분 / 그 밖 6.5분
#: 외곽 지선은 실제로 배차가 길다. 방법이 틀린 것이 아니라 재는 대상이 다른 것이다.
REFERENCE_SCOPE_STATIONS: dict[str, set[str]] = {
    "1": {"서울역", "시청", "종각", "종로3가", "종로5가", "동대문", "신설동", "제기동", "청량리"},
    "4": {
        "당고개", "상계", "노원", "창동", "쌍문", "수유", "미아", "길음", "성신여대입구",
        "한성대입구", "혜화", "동대문", "충무로", "명동", "회현", "서울역", "숙대입구",
        "삼각지", "신용산", "이촌", "동작", "총신대입구", "사당", "남태령",
    },
}

#: 산출값이 참조값의 몇 배까지 벌어지면 의심할 것인가.
#: 종착지별로 잘못 묶으면 2~4배가 된다 (1호선 8시대 4.5분 → 13.0분, 실측 3분).
REFERENCE_MAX_RATIO = 2.0


# ──────────────────────────────────────────────────────────────────────────────
# 순수 함수
# ──────────────────────────────────────────────────────────────────────────────


def parse_hms(value: object) -> float:
    """`HH:MM:SS` 를 자정 이후 초로. 영업일 기준이라 24시를 넘는 값이 실제로 있다.

    원본 최대값이 `25:14:00` 이다. `datetime.time` 으로 파싱하면 그대로 깨진다.
    """
    if not isinstance(value, str):
        return np.nan
    parts = value.strip().split(":")
    if len(parts) != 3:
        return np.nan
    try:
        h, m, s = (int(p) for p in parts)
    except ValueError:
        return np.nan
    return h * 3600 + m * 60 + s


def normalize_station(name: object) -> str:
    """역명 비교용 정규화 — 괄호 부기역명과 공백을 뗀다."""
    import re

    return re.sub(r"\(.*?\)", "", str(name)).replace(" ", "").strip()


def load_timetable(path: str | Path, weektag: str | None = "DAY") -> pd.DataFrame:
    """시각표를 읽어 초 단위 시각을 붙인다.

    시·종착역은 도착 또는 출발 시각이 비어 있다(약 15,000행). 서로 채운 뒤
    둘 다 없는 행만 버린다.
    """
    df = pd.read_csv(path, encoding=TIMETABLE_ENCODING, dtype=str)
    if weektag:
        df = df[df["WEEKTAG"] == weektag]

    arrive = df["STT"].map(parse_hms)
    depart = df["EDT"].map(parse_hms)
    df = df.assign(
        arrive_sec=arrive.fillna(depart),
        depart_sec=depart.fillna(arrive),
    )
    return df.dropna(subset=["arrive_sec", "depart_sec"]).reset_index(drop=True)


def inter_station_times(df: pd.DataFrame) -> pd.DataFrame:
    """역간 소요시간 — 같은 열차가 다음 역에 도착할 때까지 걸린 시간.

    `TRAIN_NO` 로 열차를 특정하고 시각순으로 정렬해 연속한 두 역을 잇는다.
    같은 구간이 하루에 여러 번 나오므로 중앙값을 쓴다.
    """
    ordered = df.sort_values(["LINE", "INOUTTAG", "TRAIN_NO", "arrive_sec"])
    grouped = ordered.groupby(["LINE", "INOUTTAG", "TRAIN_NO"], sort=False)

    ordered = ordered.assign(
        next_arrive=grouped["arrive_sec"].shift(-1),
        next_station=grouped["STATION_NM"].shift(-1),
        next_si_id=grouped["SI_ID"].shift(-1),
    )
    legs = ordered.dropna(subset=["next_arrive"]).copy()
    legs["run_sec"] = legs["next_arrive"] - legs["depart_sec"]
    legs = legs[legs["run_sec"].between(RUN_MIN_SEC, RUN_MAX_SEC)]

    out = (
        legs.groupby(["LINE", "INOUTTAG", "SI_ID", "STATION_NM", "next_si_id", "next_station"])
        .agg(run_sec=("run_sec", "median"), samples=("run_sec", "size"))
        .reset_index()
    )
    out["run_sec"] = out["run_sec"].round().astype(int)
    return out.rename(
        columns={
            "LINE": "line", "INOUTTAG": "direction",
            "SI_ID": "from_si_id", "STATION_NM": "from_station",
            "next_si_id": "to_si_id", "next_station": "to_station",
        }
    )


def headways(df: pd.DataFrame, hour: int | None = None, local_only: bool = True) -> pd.DataFrame:
    """배차간격 — 같은 역·같은 방향에 연속으로 들어오는 열차의 시각 차.

    **종착지(`ED_STT_NM`)로 더 쪼개지 않는다.** 분기 노선에서 2~4배로 부풀려진다 —
    1호선 8시대가 종착지별로는 13.0분이지만 (역, 방향)만으로는 4.5분이고
    운행현황 실측값은 3분이다. 시청역 가는 승객은 인천행이든 신창행이든 아무 하행이나 탄다.

    분기 목적지에 따라 실제 대기가 더 길어질 수 있다는 한계는 README 에 적는다 —
    정확히 다루려면 경로 의존 모델이 필요하고 이 프로젝트의 범위를 넘는다.
    """
    sub = df[df["GUBHANG"] == "0"] if local_only else df
    if hour is not None:
        sub = sub[(sub["arrive_sec"] >= hour * 3600) & (sub["arrive_sec"] < (hour + 1) * 3600)]

    ordered = sub.sort_values(["SI_ID", "INOUTTAG", "arrive_sec"])
    gap = ordered.groupby(["SI_ID", "INOUTTAG"], sort=False)["arrive_sec"].diff()
    ordered = ordered.assign(gap_sec=gap).dropna(subset=["gap_sec"])
    ordered = ordered[ordered["gap_sec"].between(HEADWAY_MIN_SEC, HEADWAY_MAX_SEC)]

    out = (
        ordered.groupby(["LINE", "SI_ID", "STATION_NM", "INOUTTAG"])
        .agg(headway_sec=("gap_sec", "median"), samples=("gap_sec", "size"))
        .reset_index()
    )
    out["headway_sec"] = out["headway_sec"].round().astype(int)
    if hour is not None:
        out.insert(0, "hour", hour)
    return out.rename(
        columns={"LINE": "line", "SI_ID": "si_id", "STATION_NM": "station", "INOUTTAG": "direction"}
    )


def headways_by_hour(df: pd.DataFrame, hours: range | None = None) -> pd.DataFrame:
    """시간대별 배차를 한 표로. 그래프 엣지의 시간대 배열에 그대로 대응한다."""
    frames = [headways(df, hour=h) for h in (hours or range(24))]
    return pd.concat([f for f in frames if not f.empty], ignore_index=True)


def check_against_reference(by_hour: pd.DataFrame, hour: int = 8) -> pd.DataFrame:
    """산출한 배차가 운행현황 실측값과 같은 자릿수인지 확인한다.

    그룹 기준을 잘못 잡으면(종착지까지 쪼개면) 여기서 배수가 튄다.
    """
    at_hour = by_hour[by_hour["hour"] == hour]

    rows = []
    for line, group in at_hour.groupby("line"):
        scope = REFERENCE_SCOPE_STATIONS.get(str(line))
        # 참조값이 재는 구간으로 좁혀서 비교한다. 좁힐 목록이 없는 호선은 전체가 곧 그 구간이다.
        scoped = group[group["station"].isin(scope)] if scope else group
        if scoped.empty:
            scoped = group

        computed = float(scoped["headway_sec"].median()) / 60
        ref = REFERENCE_HEADWAY_MIN.get(str(line))
        ref_rh = ref[0] if ref else None
        rows.append({
            "line": line,
            "computed_min": round(computed, 1),
            "reference_rh_min": ref_rh,
            "ratio": round(computed / ref_rh, 2) if ref_rh else None,
            "scoped": scope is not None,
            "ok": bool(ref_rh is None or computed / ref_rh <= REFERENCE_MAX_RATIO),
        })
    return pd.DataFrame(rows)


def join_station_coords(
    stations: pd.DataFrame, master: pd.DataFrame
) -> tuple[pd.DataFrame, pd.DataFrame]:
    """시각표의 (호선, 역명) 에 역사마스터의 위경도를 붙인다.

    `(붙은 것, 못 붙은 것)` 을 돌려준다. 못 붙은 것은 수동 매핑 대상이다.
    """
    master = master.copy()
    master["line"] = master["호선"].map(LINE_ALIAS)
    master = master.dropna(subset=["line"])
    master["key"] = master["line"] + "|" + master["역사명"].map(normalize_station)
    lookup = master.drop_duplicates("key").set_index("key")

    stations = stations.copy()
    aliased = stations.apply(
        lambda r: STATION_ALIAS.get((r["line"], r["station"]), r["station"]), axis=1
    )
    stations["key"] = stations["line"] + "|" + aliased.map(normalize_station)

    joined = stations.join(lookup[["위도", "경도"]], on="key")
    joined = joined.rename(columns={"위도": "lat", "경도": "lng"})
    joined["lat"] = pd.to_numeric(joined["lat"], errors="coerce")
    joined["lng"] = pd.to_numeric(joined["lng"], errors="coerce")

    matched = joined.dropna(subset=["lat", "lng"])
    missing = joined[joined["lat"].isna()]
    return matched.drop(columns="key"), missing.drop(columns="key")


# ──────────────────────────────────────────────────────────────────────────────
# CLI
# ──────────────────────────────────────────────────────────────────────────────


def _report(legs: pd.DataFrame, by_hour: pd.DataFrame, check: pd.DataFrame,
            matched: pd.DataFrame, missing: pd.DataFrame, rows: int) -> None:
    print(f"\n유효 행 {rows:,}\n")

    print("[역간 소요시간]")
    print(f"  고유 구간 {len(legs):,}")
    print(f"  주행(초)  중앙 {legs['run_sec'].median():.0f} · "
          f"5% {legs['run_sec'].quantile(.05):.0f} · 95% {legs['run_sec'].quantile(.95):.0f}")
    per_line = legs.groupby("line").size().to_dict()
    print(f"  호선별    {per_line}")

    print("\n[시간대별 배차 — 중앙(분)]")
    pivot = (
        by_hour[by_hour["hour"].isin([8, 12, 19, 22])]
        .pivot_table(index="line", columns="hour", values="headway_sec", aggfunc="median")
        .div(60).round(1)
    )
    print(pivot.to_string())

    print("\n[운행현황 대조 — 8시대, 참조값이 재는 구간으로 맞춤]")
    for r in check.itertuples():
        ref = f"{r.reference_rh_min}" if r.reference_rh_min else "—"
        note = " (공사 구간)" if r.scoped else ""
        mark = "" if r.ok else "  ⚠ 자릿수가 다르다. 그룹 기준을 의심할 것"
        print(f"  {r.line}호선  산출 {r.computed_min:>5.1f}분  참조 {ref:>4}분  "
              f"배수 {r.ratio if r.ratio else '—'}{note}{mark}")

    total = len(matched) + len(missing)
    print(f"\n[역 좌표 매핑]  {len(matched)}/{total} ({len(matched)/total*100:.1f}%)")
    if len(missing):
        print("  미매칭:", ", ".join(f"{r.line}|{r.station}" for r in missing.itertuples()))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="시각표 → 역간 소요시간 · 시간대별 배차")
    parser.add_argument("--timetable", default=TIMETABLE_PATH)
    parser.add_argument("--station-master", default=STATION_MASTER_PATH)
    parser.add_argument("--weektag", default="DAY", choices=WEEKTAGS)
    parser.add_argument("--out", default=None, help="결과를 저장할 디렉토리")
    args = parser.parse_args(argv)

    for p in (args.timetable, args.station_master):
        if not Path(p).exists():
            print(f"파일 없음: {p}", file=sys.stderr)
            return 2

    df = load_timetable(args.timetable, args.weektag)
    legs = inter_station_times(df)
    by_hour = headways_by_hour(df)
    check = check_against_reference(by_hour)

    stations = (
        df[["LINE", "SI_ID", "STATION_NM"]]
        .drop_duplicates()
        .rename(columns={"LINE": "line", "SI_ID": "si_id", "STATION_NM": "station"})
    )
    matched, missing = join_station_coords(stations, pd.read_csv(args.station_master, encoding="cp949", dtype=str))

    _report(legs, by_hour, check, matched, missing, len(df))

    if args.out:
        out = Path(args.out)
        out.mkdir(parents=True, exist_ok=True)
        legs.to_csv(out / f"inter_station_{args.weektag}.csv", index=False, encoding="utf-8")
        by_hour.to_csv(out / f"headway_{args.weektag}.csv", index=False, encoding="utf-8")
        matched.to_csv(out / "station_coords.csv", index=False, encoding="utf-8")
        (out / f"summary_{args.weektag}.json").write_text(
            json.dumps({
                "weektag": args.weektag,
                "rows": len(df),
                "legs": len(legs),
                "run_sec_median": float(legs["run_sec"].median()),
                "stations_matched": len(matched),
                "stations_missing": len(missing),
                "reference_check": check.to_dict("records"),
            }, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"\n저장: {out}")

    return 0 if bool(check["ok"].all()) else 1


if __name__ == "__main__":
    raise SystemExit(main())
