"""지하철 시각표에서 역간 소요시간과 시간대별 배차를 산출한다.

    서울교통공사_도시철도열차운행시각표.csv  (532,832행 / 1~9호선)
        │
        ├─▶ 역간 소요시간   (LINE, INOUTTAG, TRAIN_NO) 로 묶어 연속 역의 출발 시각 차
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

#: 급행을 별도 노선으로 떼어낼 때 붙이는 꼬리표. `GUBHANG` 이 '0' 이 아니면 급행이다.
#:
#: 떼어내지 않으면 급행이 건너뛴 구간(고속터미널→동작 215초, 4역 건너뜀)이 완행 구간
#: (고속터미널→신반포 105초)과 **같은 플랫폼 노드**에 붙는다. 그러면 완행 배차로 기다린 뒤
#: 급행처럼 건너뛰고, 급행↔완행 전환도 공짜인 경로가 만들어진다. 실제로 그 탓에
#: 강남→홍대입구가 2호선 직결 대신 9호선 급행 3회 환승으로 나왔다.
#:
#: 노선을 나누면 플랫폼이 쪼개져 (1) 급행은 급행 배차로 기다리고 (2) 급행↔완행 전환이
#: TRANSFER 비용을 물게 된다. 급행을 버리지 않고 제값에 태우는 것이 목적이다.
EXPRESS_SUFFIX = "급행"

#: 역간 구간시간(주행 + 도착역 정차)으로 인정할 범위(초). 이 밖은 회차·주박 등 운행 외 구간이다.
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


def line_key(line: object, gubhang: object) -> str:
    """(호선, 급행구분) → 그래프가 쓸 노선 식별자. 급행이면 `9급행` 처럼 꼬리표가 붙는다."""
    return f"{line}{EXPRESS_SUFFIX}" if str(gubhang) != "0" else str(line)


def base_line(key: object) -> str:
    """`9급행` → `9`. 역 좌표·환승 실측값은 급행/완행을 가리지 않으므로 이 값으로 찾는다."""
    text = str(key)
    return text[: -len(EXPRESS_SUFFIX)] if text.endswith(EXPRESS_SUFFIX) else text


def is_express(key: object) -> bool:
    return str(key).endswith(EXPRESS_SUFFIX)


def normalize_station(name: object) -> str:
    """역명 비교용 정규화 — 괄호 부기역명과 공백을 뗀다."""
    import re

    return re.sub(r"\(.*?\)", "", str(name)).replace(" ", "").strip()


def load_timetable(path: str | Path, weektag: str | None = "DAY") -> pd.DataFrame:
    """시각표를 읽어 초 단위 시각을 붙인다.

    시·종착역은 도착 또는 출발 시각이 비어 있다(약 15,000행). 서로 채운 뒤
    둘 다 없는 행만 버린다.

    `line_key` 를 함께 붙인다 — 이후 모든 집계는 `LINE` 이 아니라 이 값으로 묶는다.
    급행을 별도 노선으로 보기 위해서다(EXPRESS_SUFFIX 주석 참조).
    """
    df = pd.read_csv(path, encoding=TIMETABLE_ENCODING, dtype=str)
    if weektag:
        df = df[df["WEEKTAG"] == weektag]

    arrive = df["STT"].map(parse_hms)
    depart = df["EDT"].map(parse_hms)
    df = df.assign(
        arrive_sec=arrive.fillna(depart),
        depart_sec=depart.fillna(arrive),
        line_key=[line_key(l, g) for l, g in zip(df["LINE"], df["GUBHANG"])],
    )
    return df.dropna(subset=["arrive_sec", "depart_sec"]).reset_index(drop=True)


def inter_station_times(df: pd.DataFrame) -> pd.DataFrame:
    """역간 소요시간 — 같은 열차가 이 역을 떠나 다음 역을 떠날 때까지 걸린 시간.

    `TRAIN_NO` 로 열차를 특정하고 시각순으로 정렬해 연속한 두 역을 잇는다.
    같은 구간이 하루에 여러 번 나오므로 중앙값을 쓴다.

    **출발 → 다음 역 출발**이다. 주행시간만 재면(다음 역 *도착* − 이 역 출발) 이어 붙였을 때
    중간 역의 정차가 통째로 사라진다. A→B→C 를 더하면 `(B도착−A출발) + (C도착−B출발)` 이 되어
    `B출발−B도착`, 즉 B 에 서 있던 시간이 빠진다. 정차 중앙값은 1~9호선 전부 30초이고
    17정차짜리 경로에서는 8분이 없어진다 — 화곡→종로3가가 시각표 실측 31.2분인데
    23.8분으로 나왔다. 다음 역 *출발*을 기준으로 잡으면 각 엣지가 그 역의 정차를 품게 되어
    이어 붙일 때 자동으로 누적된다(같은 경로 31.7분).

    남는 오차는 **하차역 정차 30초**다. 마지막 엣지가 목적지의 정차까지 포함하는데 승객은
    그만큼 서 있지 않는다. 정차를 엣지가 아니라 노드 비용으로 옮기면 없앨 수 있지만
    그래프 구조를 바꿔야 하고, 경로 길이와 무관한 고정 30초라 그대로 둔다.

    묶는 단위는 `LINE` 이 아니라 `line_key` 다. 급행이 건너뛴 구간은 급행 노선의 구간이지
    9호선 구간이 아니다 — 섞으면 완행 플랫폼에서 급행처럼 건너뛸 수 있게 된다.
    """
    ordered = df.sort_values(["line_key", "INOUTTAG", "TRAIN_NO", "arrive_sec"])
    grouped = ordered.groupby(["line_key", "INOUTTAG", "TRAIN_NO"], sort=False)

    ordered = ordered.assign(
        next_depart=grouped["depart_sec"].shift(-1),
        next_station=grouped["STATION_NM"].shift(-1),
        next_si_id=grouped["SI_ID"].shift(-1),
    )
    legs = ordered.dropna(subset=["next_depart"]).copy()
    legs["run_sec"] = legs["next_depart"] - legs["depart_sec"]
    legs = legs[legs["run_sec"].between(RUN_MIN_SEC, RUN_MAX_SEC)]

    out = (
        legs.groupby(["line_key", "INOUTTAG", "SI_ID", "STATION_NM", "next_si_id", "next_station"])
        .agg(run_sec=("run_sec", "median"), samples=("run_sec", "size"))
        .reset_index()
    )
    out["run_sec"] = out["run_sec"].round().astype(int)
    return out.rename(
        columns={
            "line_key": "line", "INOUTTAG": "direction",
            "SI_ID": "from_si_id", "STATION_NM": "from_station",
            "next_si_id": "to_si_id", "next_station": "to_station",
        }
    )


def headways(df: pd.DataFrame, hour: int | None = None) -> pd.DataFrame:
    """배차간격 — 같은 역·같은 방향·**같은 등급**에 연속으로 들어오는 열차의 시각 차.

    **종착지(`ED_STT_NM`)로 더 쪼개지 않는다.** 분기 노선에서 2~4배로 부풀려진다 —
    1호선 8시대가 종착지별로는 13.0분이지만 (역, 방향)만으로는 4.5분이고
    운행현황 실측값은 3분이다. 시청역 가는 승객은 인천행이든 신창행이든 아무 하행이나 탄다.

    **급행과 완행은 쪼갠다.** 둘은 다른 승강장에서 기다리는 다른 열차다. 섞으면 배차가
    실제보다 짧아 보인다 — 완행끼리 6분인 역에 급행 한 대가 끼면 3분으로 나온다.
    `line_key` 로 묶는 것이 그 구분이며, 이 함수가 급행 노선의 배차도 함께 돌려준다.

    분기 목적지에 따라 실제 대기가 더 길어질 수 있다는 한계는 README 에 적는다 —
    정확히 다루려면 경로 의존 모델이 필요하고 이 프로젝트의 범위를 넘는다.
    """
    sub = df
    if hour is not None:
        sub = sub[(sub["arrive_sec"] >= hour * 3600) & (sub["arrive_sec"] < (hour + 1) * 3600)]

    ordered = sub.sort_values(["line_key", "SI_ID", "INOUTTAG", "arrive_sec"])
    gap = ordered.groupby(["line_key", "SI_ID", "INOUTTAG"], sort=False)["arrive_sec"].diff()
    ordered = ordered.assign(gap_sec=gap).dropna(subset=["gap_sec"])
    ordered = ordered[ordered["gap_sec"].between(HEADWAY_MIN_SEC, HEADWAY_MAX_SEC)]

    out = (
        ordered.groupby(["line_key", "SI_ID", "STATION_NM", "INOUTTAG"])
        .agg(headway_sec=("gap_sec", "median"), samples=("gap_sec", "size"))
        .reset_index()
    )
    out["headway_sec"] = out["headway_sec"].round().astype(int)
    if hour is not None:
        out.insert(0, "hour", hour)
    return out.rename(
        columns={
            "line_key": "line", "SI_ID": "si_id",
            "STATION_NM": "station", "INOUTTAG": "direction",
        }
    )


def headways_by_hour(df: pd.DataFrame, hours: range | None = None) -> pd.DataFrame:
    """시간대별 배차를 한 표로. 그래프 엣지의 시간대 배열에 그대로 대응한다."""
    frames = [headways(df, hour=h) for h in (hours or range(24))]
    return pd.concat([f for f in frames if not f.empty], ignore_index=True)


def check_against_reference(by_hour: pd.DataFrame, hour: int = 8) -> pd.DataFrame:
    """산출한 배차가 운행현황 실측값과 같은 자릿수인지 확인한다.

    그룹 기준을 잘못 잡으면(종착지까지 쪼개면) 여기서 배수가 튄다.

    급행은 뺀다 — 운행현황의 시격은 일반 열차 기준이라 비교 대상이 아니다.
    """
    at_hour = by_hour[(by_hour["hour"] == hour) & ~by_hour["line"].map(is_express)]

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

    # 좌표는 급행/완행을 가리지 않는다. 같은 역의 같은 자리다 — 기준 호선으로 찾는다.
    stations = stations.copy()
    base = stations["line"].map(base_line)
    aliased = [
        STATION_ALIAS.get((b, s), s) for b, s in zip(base, stations["station"])
    ]
    stations["key"] = base + "|" + pd.Series(aliased, index=stations.index).map(normalize_station)

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
    print(f"  구간(초)  중앙 {legs['run_sec'].median():.0f} · "
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
