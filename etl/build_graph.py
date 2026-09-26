"""경로탐색 그래프 빌드 — 보행망 + 지하철을 하나의 그래프로.

    [보행]  OSM 교차점
       │ ACCESS   진출입 도보
    [정류장]  서울역
       │ BOARD    대기 = 배차 ÷ 2        (반대는 ALIGHT, 0초)
    [플랫폼]  서울역·1호선
       │ RIDE     역간 소요시간
    [플랫폼]  시청역·1호선

노드를 두 겹으로 나눈 이유는 대기시간을 붙일 자리 때문이다. 역 노드가 하나뿐이면
"새로 타는 것"과 "계속 타고 가는 것"을 구분할 수 없어 연속 승차에도 대기가 붙는다.
환승은 플랫폼끼리 직접 잇는다(TRANSFER) — 정류장을 경유시키면 최초 승차와
구분하지 못해 환승 도보시간을 붙일 자리가 없다.

버스는 넣지 않는다. 계획서상 3주 후반이며, 같은 구조에 노드·엣지만 추가하면 된다.

사용법
-----
    python -m etl.build_graph
    python -m etl.build_graph --graphml etl/out/graph/seoul_walk.graphml --weektag DAY
"""

from __future__ import annotations

import argparse
import csv
import io
import json
import re
import sys
import time
from pathlib import Path

import numpy as np
import pandas as pd

from etl.db import connect
from etl.subway_timing import (
    EXPRESS_SUFFIX,
    STATION_MASTER_PATH,
    TIMETABLE_PATH,
    base_line,
    headways_by_hour,
    inter_station_times,
    is_express,
    join_station_coords,
    load_timetable,
    station_key,
    parse_hms,
)

GRAPHML_PATH = "etl/out/graph/seoul_walk.graphml"
#: 환승 도보시간은 두 파일에서 온다.
#:
#:   MEASURED  수도권 도시철도 환승 데이터 (OA-22521). 내린 칸 → 탈 칸 단위로 잰 값.
#:             1~8호선끼리는 99쌍 전부 있다. 9호선 1단계는 전부 10:00 자리값이다 —
#:             운영사(서울시메트로9호선)가 달라 공사가 재지 않은 것으로 보인다.
#:   DISTANCE  환승역거리 소요시간 정보. 소요시간이 잰 값이 아니라 `환승거리 ÷ 1.2m/s` 다
#:             (140행 전부 ±1초). 평지를 걷는다고 본 값이라 계단·승강장 보행이 빠져 있다.
#:
#: MEASURED 를 쓰고, 거기 없는 쌍(사실상 9호선)만 DISTANCE 를 MEASURED 기준으로 보정해 쓴다.
#: calibrate_transfers 주석 참조.
MEASURED_TRANSFER_PATH = "csv/지하철/서울교통공사_수도권 도시철도 환승 데이터_20250317.csv"
TRANSFER_PATH = "csv/지하철/서울교통공사_환승역거리 소요시간 정보_20250331.csv"

#: MEASURED 파일이 값을 모를 때 넣어 둔 자리값(10:00). 잰 값이 아니므로 버린다.
TRANSFER_PLACEHOLDER_SEC = 600

BUS_SECTION_PATH = "csv/버스/tpss_route_section_speedh_2026.08.31-09.06.csv"
BUS_ROUTE_PATH = "csv/버스/서울시 노선마스터 정보.csv"
BUS_STOP_PATH = "csv/버스/서울시 정류장마스터 정보.csv"
BUS_HEADWAY_PATH = "csv/버스/서울시버스노선기본정보(20260902).xlsx"
BUS_ROUTE_ID_PATH = "csv/버스/서울시버스노선ID정보(20260902).xlsx"

#: 그래프에 넣지 않는 버스 노선 유형. 서울시 정류장마스터에 좌표가 없어
#: 붙일 자리가 없다 (경기 12.7% / 인천 15.0% / 광역 30.9%).
BUS_EXCLUDED_TYPES = {"경기", "인천", "광역"}

#: 요일 구분 → 배차 시트 이름. 시각표의 WEEKTAG 와 맞춘다.
BUS_HEADWAY_SHEET = {"DAY": "평일공동배차", "SAT": "토요일공동배차", "END": "공휴일공동배차"}

#: 버스 구간 운행시간으로 인정할 범위(초). 0 은 그 시간대에 운행이 없다는 뜻이고,
#: 지나치게 긴 값은 회차·차고지 구간이다.
BUS_RUN_MIN_SEC, BUS_RUN_MAX_SEC = 1, 3_600

#: 도보 속도(m/s). 계획서 §4 의 walkSpeed 기본값과 같아야 한다.
WALK_SPEED_MPS = 1.2

#: 두 환승 파일 어디에도 없는 쌍에 쓸 값(초). 계획서가 "근거 없는 값"이라 지적한 180초이며,
#: 실제로 쓰인 비율을 graph_build.transfer_fallback_ratio 에 남긴다.
TRANSFER_FALLBACK_SEC = 180

#: 같은 역에서 급행 ↔ 완행을 갈아타는 도보시간(초).
#:
#: 환승 데이터에는 이 쌍이 없다. 다른 호선으로 갈아타는 것이 아니라 같은 호선 안에서
#: 열차 등급만 바꾸는 것이기 때문이다. TRANSFER_FALLBACK_SEC(180초)를 그대로 쓰면
#: 급행이 과도하게 불리해진다 — 9호선 급행 정차역은 대부분 같은 승강장 맞은편이라
#: 계단을 오르내리지 않는다.
#:
#: 승강장 ↔ 대합실 편도(STATION_TRAVERSE_RATIO 로 유도되는 값, 환승값 중앙 기준 약 90초)보다
#: 짧아야 한다는 것이 유일한 근거다. 같은 층에서 건너가는 것이 대합실까지 올라가는 것보다
#: 쌀 수밖에 없다. 30초로 둔다 — 정차시간 중앙값과 같은 자릿수다.
#:
#: 이 값이 0 이면 안 된다. 0 이면 급행/완행을 공짜로 오가며 각 구간에서 빠른 쪽만 골라
#: 타는, 분리하기 전과 똑같은 경로가 다시 생긴다.
EXPRESS_TRANSFER_SEC = 30

#: 배차를 모르는 노선·승강장에 대기를 지어내지 않는다. 예전에는 BOARD_FALLBACK_SEC(300초,
#: 배차 10분 가정)를 붙였는데 근거가 없었고, 실제로 그 값을 받던 곳은 두 부류뿐이었다.
#:
#:   버스 4개 노선      새벽A148·A160·A504·A741. 배차간격 0, 첫차=막차=03:30 — 하루 1회 운행
#:   지하철 41개 승강장  시각표의 `00:00:00` 빈칸을 자정으로 읽고, 배차를 시간대 안에서만
#:                     재서 생긴 결측(subway_timing.MISSING_TIME, headways 참조)
#:
#: 둘 다 고치고 나면 폴백이 필요한 곳이 없다. 앞으로 생기면 그 노선·승강장은 **태우지 않고
#: 세어서 보고한다.** 모르는 값을 5분으로 채우면 하루 한 대 다니는 노선이 5분마다 오는
#: 노선이 된다 — 없는 편이 덜 틀린다.

#: 시간대 배열에서 "이 시간에는 운행하지 않는다"를 뜻하는 값. 탐색은 이 엣지를 건너뛴다.
#:
#: 예전에는 운행 없는 시간대를 그 구간의 중앙값으로 채웠다. 새벽 0~4시에만 다니는 N26 이
#: 그 때문에 낮 19시간 동안 다니는 노선이 됐고, 새벽 4시에는 운행하지 않는 지하철이 다녔다.
#: 0 이 아니라 음수를 쓰는 이유는 0초가 "공짜로 지나간다"로 읽히기 때문이다.
NO_SERVICE = -1

#: 버스 구간이 그 시간대에 "운행한다"고 볼 최소 비율. 요일 구분에 드는 날 중 과반이다.
#: 평일 5일 중 3일 이상 기록이 있어야 한다. 하루 이틀만 찍힌 값은 첫차·막차 경계에서
#: 우연히 걸친 것이라 그 시간대의 정규 운행으로 보기 어렵다.
BUS_SERVICE_MIN_SHARE = 0.5

#: 승강장 ↔ 대합실 이동시간을 환승 소요시간의 몇 배로 볼 것인가.
#:
#: 환승은 승강장 → 대합실 → 승강장 이므로 편도는 그 절반이다. 상수를 새로 만들지 않고
#: 환승 소요시간에서 유도한다 — 계획서가 transferPenalty 180초를 "근거 없는 값"이라고
#: 지적했던 것과 같은 잘못을 반복하지 않기 위해서다.
#:
#: 이 값이 필요한 이유는 **환승이 공짜가 되는 것을 막기 위해서**다. 진출입이 싸면
#: 승강장 → 정류장 → 승강장 으로 갈아타는 쪽이 환승 통로보다 저렴해져 환승
#: 도보시간이 통째로 빠진다(실제로 을지로4가에서 2→5호선이 1초에 갈아타졌다).
#:
#: 0.5 로 두면 성질이 수식으로 보장된다.
#:     나갔다 다시 타기 = T + ACCESS + ACCESS + T + 대기 = 환승도보 + 2·ACCESS + 대기
#:     환승 통로       =                                    환승도보 +            대기
#: 항상 2·ACCESS 만큼 비싸다. 역마다 환승시간이 달라도 깨지지 않는다 —
#: 전역 상수를 쓰면 환승 262초짜리 역(최대값)에서 뒤집힌다.
#:
#: **대합실에서 바로 다시 타는 길은 이것으로 보장되지 않는다.**
#:     대합실 경유 = ALIGHT(a) + BOARD(b) = T(a) + T(b) + 대기
#: 역 하나에 T 하나면 이 값은 그 역 환승값의 중앙값이라, 중앙값보다 긴 쌍은 대합실 쪽이
#: 싸다(종로3가 1→5 는 326초인데 176초로 갈아타졌다). 그래서 T 를 승강장마다 두고,
#: 모자란 쌍에서만 올린다 — platform_traverse_seconds.
STATION_TRAVERSE_RATIO = 0.5

#: 역 ↔ 보행망 스냅이 이보다 멀면 경고한다. 역 출입구가 보행망에 안 잡힌 것이다.
SNAP_WARN_M = 300.0

#: 이보다 멀면 ACCESS 엣지를 아예 만들지 않는다.
#: 보행망은 서울시 경계인데 시각표는 양주·인천·신창까지 담고 있어, 서울 밖 역은
#: 수십 km 떨어진 노드에 붙는다. 그대로 두면 "수십 km 를 걸어 역에 간다"는 경로가 생긴다.
ACCESS_MAX_M = 1_000.0

TARGET_EPSG = 5186


# ──────────────────────────────────────────────────────────────────────────────
# 순수 함수
# ──────────────────────────────────────────────────────────────────────────────


def walk_seconds(distance_m: float, speed_mps: float = WALK_SPEED_MPS) -> int:
    """도보 거리를 초로. 0m 도 0초로 두지 않는다 — 가중치가 0이면 경로가 뭉갠다."""
    return max(1, round(distance_m / speed_mps))


def normalize_line(value: object) -> str | None:
    """환승 데이터의 '4호선' 같은 표기를 시각표의 '4' 로 맞춘다.

    숫자 호선만 다룬다. 공항철도·경의중앙선 등은 시각표에 없어 그래프에 넣지 않으므로
    None 을 돌려 걸러낸다.
    """
    if not isinstance(value, str):
        return None
    m = re.fullmatch(r"\s*(\d)\s*호선\s*", value)
    return m.group(1) if m else None


def load_transfers(path: str | Path) -> pd.DataFrame:
    """DISTANCE 파일의 환승 도보시간. `(역명, 호선A, 호선B, 초)` 로 정규화한다.

    원본은 한 방향만 담고 있어(1호선→4호선) 반대도 만들어 둔다 — 환승은 양방향이다.
    """
    df = pd.read_csv(path, encoding="cp949", dtype=str)
    df["line_a"] = df["호선"].str.strip()
    df["line_b"] = df["환승노선"].map(normalize_line)
    df["station"] = df["환승역명"].map(station_key)
    df["sec"] = df["환승소요시간"].map(lambda v: parse_hms(f"00:{v}") if isinstance(v, str) else np.nan)

    ok = df.dropna(subset=["line_b", "sec"])
    ok = ok[ok["line_a"] != ok["line_b"]]
    forward = ok[["station", "line_a", "line_b", "sec"]]
    backward = forward.rename(columns={"line_a": "line_b", "line_b": "line_a"})

    both = pd.concat([forward, backward], ignore_index=True)
    both["sec"] = both["sec"].round().astype(int)
    return both.drop_duplicates(subset=["station", "line_a", "line_b"])


def load_measured_transfers(path: str | Path) -> pd.DataFrame:
    """MEASURED 파일의 환승 도보시간. `(역명, 호선A, 호선B, 초)`.

    원본은 방향과 하차·승차 칸마다 한 행이다(서울역 1→4 만 네 행). 그래프의 환승은
    (역 × 호선) 플랫폼끼리 잇고 칸을 모르므로 **쌍마다 중앙값**을 쓴다. 방향은 원본에
    양쪽이 다 있어 만들지 않는다.

    버리는 것:
      - 10:00 자리값 (TRANSFER_PLACEHOLDER_SEC)
      - 숫자 호선이 아닌 쪽 (공항철도·신림선 등 — 시각표에 없어 그래프에 없다)
      - 출발역과 도착역이 다른 역인 행. 총신대입구 4 ↔ 이수 7 은 이름만 다른 같은 역이라
        station_key 가 하나로 묶어 남는다(SAME_STATION).
    """
    df = pd.read_csv(path, encoding="cp949", dtype=str)
    df = df[
        df["환승시작 호선"].str.fullmatch(r"\d")
        & df["환승종료 호선"].str.fullmatch(r"\d")
        & (df["환승시작역"].map(station_key) == df["환승종료역"].map(station_key))
    ]
    out = pd.DataFrame({
        "station": df["환승시작역"].map(station_key),
        "line_a": df["환승시작 호선"],
        "line_b": df["환승종료 호선"],
        "sec": df["소요시간"].map(lambda v: parse_hms(f"00:{v}") if isinstance(v, str) else np.nan),
    }).dropna(subset=["sec"])
    out = out[(out["line_a"] != out["line_b"]) & (out["sec"] != TRANSFER_PLACEHOLDER_SEC)]
    pairs = out.groupby(["station", "line_a", "line_b"], as_index=False)["sec"].median()
    pairs["sec"] = pairs["sec"].round().astype(int)
    return pairs


def calibrate_transfers(
    measured: pd.DataFrame, distance: pd.DataFrame
) -> tuple[pd.DataFrame, int, int]:
    """두 환승 파일을 합친다. `(환승표, 보정초, 보정에 쓴 쌍 수)`.

    MEASURED 에 있는 쌍은 그 값을 쓴다. 없는 쌍은 DISTANCE 값에 **보정초**를 더한다.

    보정초는 두 파일에 다 있는 쌍에서 `MEASURED − DISTANCE` 의 중앙값이다(2025년 파일로
    86쌍, +88초). DISTANCE 는 평지 보행이라 계단·에스컬레이터와 승강장을 걷는 시간이
    빠져 있고, 그 몫이 거리와 거의 상관없이 붙는다 — 충무로 3→4 는 17m(14초)인데
    MEASURED 는 101초다.

    **보정하지 않으면 안 된다.** 1~8호선만 MEASURED 로 바꾸면 그쪽 환승은 평균 88초
    비싸지고 9호선 환승만 싼 값으로 남아, 경로가 9호선으로 쏠린다. 정확도 이전에 두 값의
    기준을 맞추는 일이다. 겹치는 쌍으로 하나씩 빼고 맞춰 보면 오차 중앙값이
    88초 → 28초로 준다(회귀·깊이를 넣어도 27~29초로 나아지지 않는다).

    가정은 하나다 — 9호선 환승의 고정 비용이 1~8호선과 같다. 9호선 1단계는 깊이 자료도
    없어 확인할 수 없다.

    `source` 칸에 MEASURED / CALIBRATED 를 남긴다.
    """
    keys = ["station", "line_a", "line_b"]
    both = measured.merge(distance, on=keys, suffixes=("", "_dist"))
    if both.empty:
        raise ValueError("두 환승 파일에 겹치는 쌍이 없어 보정할 수 없다")
    offset = int(round(float((both["sec"] - both["sec_dist"]).median())))

    only_dist = distance.merge(measured[keys], on=keys, how="left", indicator=True)
    only_dist = only_dist[only_dist["_merge"] == "left_only"][keys + ["sec"]]
    calibrated = only_dist.assign(sec=only_dist["sec"] + offset, source="CALIBRATED")

    table = pd.concat(
        [measured[keys + ["sec"]].assign(source="MEASURED"), calibrated], ignore_index=True
    )
    table["sec"] = table["sec"].clip(lower=1).astype(int)
    return table, offset, len(both)


def subway_service_hours(timetable: pd.DataFrame) -> dict[tuple[str, str], set[int]]:
    """(노선, 역명) → 열차가 한 대라도 서는 시간대.

    자정 이후는 `24:xx`·`25:xx` 로 적혀 있으므로 24 로 나눈 나머지를 쓴다 — 25:14 는 1시다.
    """
    hour = (timetable["arrive_sec"] // 3600).astype(int) % 24
    keyed = pd.DataFrame({
        "line": timetable["line_key"],
        "key": timetable["STATION_NM"].map(station_key),
        "hour": hour,
    }).drop_duplicates()
    return {k: set(g["hour"]) for k, g in keyed.groupby(["line", "key"])}


def board_weights_by_hour(
    headways: pd.DataFrame, service: dict[tuple[str, str], set[int]] | None = None
) -> dict[tuple[str, str], list[int]]:
    """(호선, 역명) → 시간대별 대기시간 24개. 운행하지 않는 시간대는 NO_SERVICE.

    대기는 배차의 절반으로 본다 — 언제 도착할지 모르고 오면 평균적으로 그렇다.
    열차는 서는데 배차 표본이 없는 시간대(첫차 시간대 등)는 앞뒤 값으로 메운다.
    표본이 하나도 없는 승강장은 여기 키가 없고, BOARD 를 만들지 않는다.

    `service` 가 없으면 24시간 운행으로 본다(테스트용).

    방향(상/하행)은 합친다. 플랫폼 노드가 (역 × 노선) 이라 방향을 나누지 않기 때문이다 —
    나누면 노드가 배로 늘고, 배차는 방향별 차이가 크지 않다.
    """
    keyed = headways.assign(key=headways["station"].map(station_key))
    out: dict[tuple[str, str], list[int]] = {}
    for (line, key), group in keyed.groupby(["line", "key"]):
        per_hour = group.groupby("hour")["headway_sec"].median()
        series = pd.Series(
            [per_hour.get(h, np.nan) for h in range(24)], index=range(24), dtype=float
        )
        filled = series.ffill().bfill()
        runs = service.get((line, key), set()) if service is not None else set(range(24))
        out[(line, key)] = [
            max(1, round(v / 2)) if h in runs else NO_SERVICE for h, v in enumerate(filled)
        ]
    return out


def hourly_literal(values, offset: int = 0) -> str:
    """시간대 배열을 PostgreSQL 배열 리터럴로. NO_SERVICE 칸에는 offset 을 더하지 않는다."""
    return "{" + ",".join(
        str(NO_SERVICE if v == NO_SERVICE else v + offset) for v in values
    ) + "}"


def representative(values) -> int:
    """시간대 배열의 대표값 — 운행하는 시간대의 중앙값. 시간대를 안 쓰는 탐색이 이 값을 본다."""
    running = [v for v in values if v != NO_SERVICE]
    return int(round(float(np.median(running)))) if running else NO_SERVICE


def station_traverse_seconds(transfers: pd.DataFrame) -> tuple[dict[str, int], int]:
    """역별 승강장 ↔ 대합실 이동시간(초). `(역명 → 초, 기본값)`.

    그 역의 환승 소요시간 중앙값의 절반으로 본다. 환승이 승강장 → 대합실 → 승강장 이므로
    편도는 그 절반이라는 것이 유일한 가정이고, 나머지는 전부 환승 데이터에서 온다.

    환승 데이터가 없는 역(단일 노선이라 환승할 일도 없다)은 전체 중앙값의 절반을 쓴다.
    """
    per_station = transfers.groupby("station")["sec"].median()
    default = max(1, round(float(transfers["sec"].median()) * STATION_TRAVERSE_RATIO))
    return (
        {k: max(1, round(v * STATION_TRAVERSE_RATIO)) for k, v in per_station.items()},
        default,
    )


def platform_traverse_seconds(
    transfers: pd.DataFrame, traverse: dict[str, int], default: int
) -> dict[tuple[str, str], int]:
    """(역명, 기준 호선) → 승강장 ↔ 대합실 이동시간. 역 값에서 시작해 모자란 승강장만 올린다.

    조건은 모든 환승 쌍에서 `T(a) + T(b) > 환승도보(a→b)` — 대합실로 나갔다 다시 타는 쪽이
    환승 통로보다 1초라도 비싸야 한다. 같으면 탐색이 어느 쪽을 고를지 정해지지 않는다.

    모자라면 차이를 두 승강장에 반씩 나눠 올린다. 한 쌍만 긴 역(종로3가 1↔5)에서 역 전체를
    올리면 그 역에서 타고 내리는 모든 경로가 비싸진다. 올리기만 하므로 이미 맞춘 쌍이 다시
    깨지지 않고, 반복은 몇 바퀴 안에 끝난다.

    여기 없는 승강장은 `traverse` 의 역 값(또는 `default`)을 쓴다.
    """
    t: dict[tuple[str, str], int] = {}
    for r in transfers.itertuples():
        for line in (r.line_a, r.line_b):
            t.setdefault((r.station, line), traverse.get(r.station, default))

    changed = True
    while changed:
        changed = False
        for r in transfers.itertuples():
            a, b = (r.station, r.line_a), (r.station, r.line_b)
            short = int(r.sec) + 1 - (t[a] + t[b])
            if short > 0:
                t[a] += (short + 1) // 2
                t[b] += short // 2
                changed = True
    return t


def summarize_snap(distances: list[float]) -> dict:
    """스냅 거리 분포. 이 값이 크면 그 역은 사실상 걸어서 못 닿는 역이 된다."""
    if not distances:
        return {"count": 0}
    s = pd.Series(distances)
    return {
        "count": int(len(s)),
        "median_m": round(float(s.median()), 1),
        "p95_m": round(float(s.quantile(0.95)), 1),
        "max_m": round(float(s.max()), 1),
        "over_warn": int((s > SNAP_WARN_M).sum()),
    }


# ──────────────────────────────────────────────────────────────────────────────
# 적재 단계
# ──────────────────────────────────────────────────────────────────────────────


def copy_rows(cur, table: str, columns: list[str], rows) -> int:
    """COPY 로 벌크 적재. rows 는 튜플 이터러블."""
    buf = io.StringIO()
    writer = csv.writer(buf)
    n = 0
    for row in rows:
        writer.writerow(row)
        n += 1
    buf.seek(0)
    with cur.copy(f"COPY {table} ({', '.join(columns)}) FROM STDIN WITH (FORMAT csv)") as cp:
        cp.write(buf.read())
    return n


def start_build(conn, area: str) -> int:
    with conn.cursor() as cur:
        cur.execute(
            """
            INSERT INTO graph_build (status, area, walk_speed_mps)
            VALUES ('RUNNING', %s, %s) RETURNING id
            """,
            (area, WALK_SPEED_MPS),
        )
        return cur.fetchone()[0]


def drop_build(conn, build_id: int) -> None:
    """빌드 하나를 지운다. **의존 순서대로** 지우고 CASCADE 에 기대지 않는다.

    graph_build 를 바로 지우면 노드 16.5만 × 엣지 47만 에 대해 CASCADE 가 돌면서
    사실상 끝나지 않는다(실측: 10분 넘게 진행 중이라 취소). 엣지 → 노드 → 빌드 순으로
    build_id 만 보고 지우면 인덱스를 타고 몇 초에 끝난다.
    """
    with conn.cursor() as cur:
        cur.execute("DELETE FROM graph_edge WHERE build_id = %s", (build_id,))
        cur.execute("DELETE FROM graph_node WHERE build_id = %s", (build_id,))
        cur.execute("DELETE FROM graph_build WHERE id = %s", (build_id,))


def truncate_all(conn) -> None:
    """그래프를 통째로 비운다. 처음부터 다시 만들 때만 쓴다.

    행을 전부 지울 때 DELETE 는 한 행씩 훑으며 참조를 확인해 몇 분이 걸린다(실측).
    TRUNCATE 는 테이블 파일을 버리는 것이라 1초도 걸리지 않는다.
    """
    with conn.cursor() as cur:
        cur.execute(
            "TRUNCATE graph_edge, graph_node, graph_build, transit_stop, transit_route CASCADE"
        )


def prune_old_builds(conn, keep_id: int) -> int:
    """활성 빌드와 방금 만든 것 말고는 버린다. 빌드마다 64만 행이 쌓인다."""
    with conn.cursor() as cur:
        cur.execute(
            "SELECT id FROM graph_build WHERE id <> %s AND NOT is_active", (keep_id,)
        )
        old = [r[0] for r in cur.fetchall()]
    for build_id in old:
        drop_build(conn, build_id)
    return len(old)


def load_stations(conn, stations: pd.DataFrame) -> dict[str, int]:
    """역을 transit_stop 에 넣고 `역명 → transit_stop.id` 를 돌려준다.

    **키는 SI_ID 가 아니라 역명이다.** 시각표의 SI_ID 는 역이 아니라 (역 × 호선) 단위라
    서울역이 1호선은 0150, 4호선은 0426 으로 따로 매겨진다. SI_ID 로 역을 세면
    한 역에 노선이 하나뿐인 것처럼 보여 환승 엣지가 하나도 만들어지지 않는다.
    환승 데이터도 역명으로 키가 잡혀 있으므로 역명을 역의 정체성으로 삼는다.
    """
    with conn.cursor() as cur:
        cur.execute(
            "CREATE TEMP TABLE _stop (key text, name text, lng float8, lat float8) ON COMMIT DROP"
        )
        copy_rows(
            cur, "_stop", ["key", "name", "lng", "lat"],
            ((station_key(r.station), r.station, r.lng, r.lat)
             for r in stations.itertuples()),
        )
        # 같은 역이 노선마다 조금씩 다른 좌표를 갖는다(출입구 위치). 평균을 쓴다.
        cur.execute(
            """
            INSERT INTO transit_stop (mode, source_id, name, geom)
            SELECT 'SUBWAY', key,
                   -- 이름이 여럿인 한 역(SAME_STATION)은 「총신대입구(이수)」로 보인다
                   CASE WHEN count(DISTINCT name) > 1
                        THEN key || '(' || string_agg(DISTINCT name, ',') FILTER (WHERE name <> key) || ')'
                        ELSE min(name) END,
                   ST_Transform(ST_SetSRID(ST_MakePoint(avg(lng), avg(lat)), 4326), %s)
            FROM _stop GROUP BY key
            ON CONFLICT (mode, source_id) DO UPDATE SET name = EXCLUDED.name, geom = EXCLUDED.geom
            """,
            (TARGET_EPSG,),
        )
        cur.execute("SELECT source_id, id FROM transit_stop WHERE mode = 'SUBWAY'")
        return dict(cur.fetchall())


def insert_walk_nodes_edges(conn, build_id: int, graphml: str) -> tuple[int, int]:
    """OSM 보행망을 WALK 노드·엣지로. 좌표는 4326 → 5186 변환해 넣는다."""
    import osmnx as ox

    G = ox.load_graphml(graphml)

    with conn.cursor() as cur:
        cur.execute("CREATE TEMP TABLE _wn (osmid text, lng float8, lat float8) ON COMMIT DROP")
        nodes = copy_rows(
            cur, "_wn", ["osmid", "lng", "lat"],
            ((str(n), d["x"], d["y"]) for n, d in G.nodes(data=True)),
        )
        cur.execute(
            """
            INSERT INTO graph_node (build_id, kind, source_id, geom)
            SELECT %s, 'WALK', osmid,
                   ST_Transform(ST_SetSRID(ST_MakePoint(lng, lat), 4326), %s)
            FROM _wn
            """,
            (build_id, TARGET_EPSG),
        )

        cur.execute(
            "SELECT source_id, id FROM graph_node WHERE build_id = %s AND kind = 'WALK'",
            (build_id,),
        )
        node_id = dict(cur.fetchall())

        def walk_edges():
            for u, v, data in G.edges(data=True):
                a, b = node_id.get(str(u)), node_id.get(str(v))
                if a is None or b is None:
                    continue
                length = float(data.get("length", 0.0))
                yield (build_id, a, b, "WALK", walk_seconds(length), round(length, 1))

        edges = copy_rows(
            cur, "graph_edge",
            ["build_id", "from_node_id", "to_node_id", "kind", "weight_sec", "distance_m"],
            walk_edges(),
        )
    return nodes, edges


def insert_transit_nodes(
    conn, build_id: int, stations: pd.DataFrame, stop_ids: dict[str, int]
) -> tuple[dict[str, int], dict[tuple[str, str], int]]:
    """STOP 노드(역명 하나당 1개)와 PLATFORM 노드(역명 × 노선)를 넣는다.

    `(역명 → STOP node id, (역명, 노선) → PLATFORM node id)` 를 돌려준다.
    키가 SI_ID 가 아닌 역명인 이유는 `load_stations` 주석 참조.
    """
    with conn.cursor() as cur:
        # STOP — 역 하나당 1개. 진출입과 환승의 허브.
        # 이번 시각표에 있는 역만. transit_stop 은 빌드를 넘어 남으므로 역명이 바뀌면(이수 →
        # 총신대입구) 옛 행이 남는다 — 전부 넣으면 엣지 없는 고립 노드가 된다.
        keys = sorted(set(stations["station"].map(station_key)))
        cur.execute(
            """
            INSERT INTO graph_node (build_id, kind, source_id, stop_id, geom)
            SELECT %s, 'STOP', s.source_id, s.id, s.geom
            FROM transit_stop s WHERE s.mode = 'SUBWAY' AND s.source_id = ANY(%s)
            """,
            (build_id, keys),
        )
        cur.execute(
            "SELECT source_id, id FROM graph_node WHERE build_id = %s AND kind = 'STOP'",
            (build_id,),
        )
        stop_node = dict(cur.fetchall())

        # PLATFORM — (역명, 노선). 계획서의 "노선별 역 노드 복제"가 이것이다.
        pairs = stations.assign(key=stations["station"].map(station_key))
        pairs = pairs.drop_duplicates(["key", "line"])
        cur.execute("CREATE TEMP TABLE _pf (key text, line text) ON COMMIT DROP")
        copy_rows(cur, "_pf", ["key", "line"], ((r.key, r.line) for r in pairs.itertuples()))
        cur.execute(
            """
            INSERT INTO graph_node (build_id, kind, source_id, line, stop_id, geom)
            SELECT %s, 'PLATFORM', p.key, p.line, s.id, s.geom
            FROM _pf p JOIN transit_stop s ON s.mode = 'SUBWAY' AND s.source_id = p.key
            """,
            (build_id,),
        )
        cur.execute(
            "SELECT source_id, line, id FROM graph_node WHERE build_id = %s AND kind = 'PLATFORM'",
            (build_id,),
        )
        platform_node = {(key, line): nid for key, line, nid in cur.fetchall()}

    return stop_node, platform_node


def insert_ride_edges(conn, build_id: int, legs: pd.DataFrame, platform: dict) -> int:
    """역간 소요시간 → RIDE. 플랫폼이 (역명 × 노선) 이라 방향별 구간을 합친다.

    `run_sec` 은 "이 역 출발 → 다음 역 출발"이라 **도착역 정차시간을 품고 있다**
    (`subway_timing.inter_station_times`). 이어 붙였을 때 중간 역 정차가 누적되도록
    일부러 그렇게 잡은 값이므로 여기서 빼지 않는다.
    """
    keyed = legs.assign(
        a=legs["from_station"].map(station_key),
        b=legs["to_station"].map(station_key),
    )
    merged = keyed.groupby(["line", "a", "b"])["run_sec"].median().round().astype(int)

    def rows():
        for (line, a, b), sec in merged.items():
            fa, fb = platform.get((a, line)), platform.get((b, line))
            if fa is None or fb is None or fa == fb:
                continue
            yield (build_id, fa, fb, "RIDE", int(sec), line)

    with conn.cursor() as cur:
        return copy_rows(
            cur, "graph_edge",
            ["build_id", "from_node_id", "to_node_id", "kind", "weight_sec", "line"],
            rows(),
        )


def insert_board_alight_edges(
    conn, build_id: int, platform: dict, stop_node: dict, waits: dict,
    traverse: dict[str, int], traverse_default: int,
    platform_traverse: dict[tuple[str, str], int] | None = None,
) -> tuple[int, int]:
    """STOP → PLATFORM(BOARD)와 그 반대(ALIGHT). `(BOARD 수, ALIGHT 수, 배차 몰라 뺀 승강장 수)`.

    양쪽 모두 **승강장 ↔ 대합실 이동시간**을 낸다. BOARD 에는 거기에 대기가 더 붙는다.
    이 이동시간이 없으면 정류장을 거쳐 노선을 바꾸는 쪽이 환승 통로보다 싸져
    환승 도보시간이 무시된다.

    배차를 모르는 승강장에는 BOARD 를 만들지 않는다(대기를 지어내지 않는다). 내리는 것은 된다.
    """
    unpriced = 0
    platform_traverse = platform_traverse or {}

    def walk_of(key: str, line: str) -> int:
        # 급행 승강장은 같은 호선 완행과 같은 자리다 — 기준 호선으로 찾는다.
        return platform_traverse.get((key, base_line(line)), traverse.get(key, traverse_default))

    def board_rows():
        nonlocal unpriced
        for (key, line), pid in platform.items():
            sid = stop_node.get(key)
            if sid is None:
                continue
            hours = waits.get((line, key))
            if not hours:
                unpriced += 1
                continue
            walk = walk_of(key, line)
            yield (build_id, sid, pid, "BOARD", walk + representative(hours),
                   hourly_literal(hours, walk), line)

    def alight_rows():
        for (key, line), pid in platform.items():
            sid = stop_node.get(key)
            if sid is not None:
                # 내릴 때는 기다릴 것이 없다. 승강장에서 대합실까지 올라오는 시간만 낸다.
                yield (build_id, pid, sid, "ALIGHT", walk_of(key, line), line)

    with conn.cursor() as cur:
        b = copy_rows(
            cur, "graph_edge",
            ["build_id", "from_node_id", "to_node_id", "kind", "weight_sec", "weight_by_hour", "line"],
            board_rows(),
        )
        a = copy_rows(
            cur, "graph_edge",
            ["build_id", "from_node_id", "to_node_id", "kind", "weight_sec", "line"],
            alight_rows(),
        )
    return b, a, unpriced


def insert_transfer_edges(
    conn, build_id: int, stations: pd.DataFrame, platform: dict, transfers: pd.DataFrame, waits: dict
) -> tuple[int, int]:
    """같은 역의 다른 노선 플랫폼을 직접 잇는다. `(엣지 수, 폴백 수)`.

    가중치는 **환승 도보 + 새 노선 대기** 다. 정류장을 경유하지 않으므로
    BOARD 를 지나지 않고, 따라서 대기가 이중으로 붙지 않는다.

    급행 ↔ 완행도 여기를 지난다. 환승 데이터에 없는 쌍이라 EXPRESS_TRANSFER_SEC 를 쓰고,
    근거 없는 폴백(TRANSFER_FALLBACK_SEC)과 구분해 세지 않는다 — 값이 없어서 메운 것이
    아니라 다른 종류의 환승이라 다른 값을 쓰는 것이다.
    """
    lookup = {
        (r.station, r.line_a, r.line_b): int(r.sec) for r in transfers.itertuples()
    }

    by_station: dict[str, list[str]] = {}
    for (key, line) in platform:
        by_station.setdefault(key, []).append(line)

    rows, fallback = [], 0
    for key, lines in by_station.items():
        if len(lines) < 2:
            continue
        for a in lines:
            for b in lines:
                if a == b:
                    continue
                if base_line(a) == base_line(b):
                    # 같은 호선의 급행 ↔ 완행. 호선을 바꾸는 것이 아니라 등급만 바꾼다.
                    walk = EXPRESS_TRANSFER_SEC
                else:
                    # 환승 데이터는 급행/완행을 구분하지 않는다. 기준 호선으로 찾는다.
                    walk = lookup.get((key, base_line(a), base_line(b)))
                    if walk is None:
                        walk = TRANSFER_FALLBACK_SEC
                        fallback += 1
                wait_hours = waits.get((b, key))
                if not wait_hours:
                    # 갈아탈 노선의 배차를 모르면 BOARD 와 마찬가지로 태우지 않는다.
                    continue
                rows.append(
                    (build_id, platform[(key, a)], platform[(key, b)],
                     "TRANSFER", walk + representative(wait_hours),
                     hourly_literal(wait_hours, walk), b)
                )

    with conn.cursor() as cur:
        n = copy_rows(
            cur, "graph_edge",
            ["build_id", "from_node_id", "to_node_id", "kind", "weight_sec", "weight_by_hour", "line"],
            rows,
        )
    return n, fallback


# ──────────────────────────────────────────────────────────────────────────────
# 버스
# ──────────────────────────────────────────────────────────────────────────────


def load_bus_routes(route_path) -> pd.DataFrame:
    """노선마스터. 그래프에 넣을 서울 노선만 남긴다.

    `노선_ID` 는 구간·배차 데이터가 쓰는 식별자이고 `노선_명칭` 이 사람이 읽는 이름이다.
    둘을 잇는 곳은 여기뿐이라, 이 표가 없으면 경로 결과가 노선 ID 로 나온다.
    """
    routes = pd.read_csv(route_path, encoding="cp949", dtype=str)
    return routes[~routes["노선_유형"].isin(BUS_EXCLUDED_TYPES)].reset_index(drop=True)


BUS_HOUR_COLS = [f"운행시간_{h:02d}시" for h in range(24)]


def load_bus_sections(section_path, routes: pd.DataFrame, weektag: str) -> pd.DataFrame:
    """서울 버스 구간과 시간대별 운행시간. 구간 하나당 한 행, 요일 구분에 드는 날을 모두 합친다.

    구간 데이터는 하루치씩 7일이 들어 있고 WEEKTAG 컬럼이 없어 `기준_날짜` 의 요일로 고른다.
    예전에는 그중 **첫날 하루**만 썼다. 날마다 편차가 커서(6411번 18시: 월 310 · 화 325 ·
    수 269분) 그날의 사고·날씨가 그대로 그래프에 들어갔다. 이제 aggregate_bus_sections 로 합친다.
    """
    cols = ["기준_날짜", "노선_ID", "출발_정류장_ID", "도착_정류장_ID"] + BUS_HOUR_COLS
    sec = pd.read_csv(section_path, encoding="cp949", dtype=str, usecols=cols)

    dates = sorted(sec["기준_날짜"].unique())
    want = {"DAY": range(0, 5), "SAT": [5], "END": [6]}[weektag]
    picked = [d for d in dates if pd.Timestamp(d).dayofweek in want]

    seoul = set(routes["노선_ID"])
    sec = sec[sec["기준_날짜"].isin(picked) & sec["노선_ID"].isin(seoul)]
    return aggregate_bus_sections(sec, days=len(picked))


def aggregate_bus_sections(sec: pd.DataFrame, days: int) -> pd.DataFrame:
    """날짜별 구간 행을 구간 하나로 합친다. 시간대 칸은 초, 또는 NO_SERVICE.

    원본의 0 은 "그 시간대에 이 구간을 지난 버스가 없다"는 뜻이다. 시간대마다
    **운행한 날이 과반**(BUS_SERVICE_MIN_SHARE)이면 운행한 날들의 중앙값을, 아니면
    NO_SERVICE 를 쓴다. 0 을 날짜 중앙값에 섞으면 운행시간이 짧아 보이므로 섞지 않는다.
    """
    keys = ["노선_ID", "출발_정류장_ID", "도착_정류장_ID"]
    values = sec[BUS_HOUR_COLS].apply(pd.to_numeric, errors="coerce")
    running = values.where(values > 0)
    grouped = pd.concat([sec[keys], running], axis=1).groupby(keys, sort=False)

    median = grouped[BUS_HOUR_COLS].median()
    count = grouped[BUS_HOUR_COLS].count()
    need = days * BUS_SERVICE_MIN_SHARE
    out = median.round().where(count > need).fillna(NO_SERVICE).astype(int)
    return out.reset_index()


def _subway_route_name(key: str) -> str:
    """`9` → `9호선`, `9급행` → `9호선 급행`. 화면에 그대로 나가는 이름이다."""
    base = base_line(key)
    name = f"{base}호선" if base.isdigit() else base
    return f"{name} {EXPRESS_SUFFIX}" if is_express(key) else name


def insert_transit_routes(conn, bus_routes: pd.DataFrame | None, subway_lines) -> int:
    """`transit_route` 를 채운다. 탐색에는 쓰이지 않고 결과를 읽을 때만 쓴다.

    지하철 노선도 함께 넣어 두 수단을 같은 방법으로 표시할 수 있게 한다 —
    `line` 이 '2' 라 그냥 읽히더라도, 읽는 쪽이 수단마다 다르게 굴면 안 된다.
    """
    rows = [
        ("SUBWAY", str(l), _subway_route_name(str(l)), EXPRESS_SUFFIX if is_express(l) else None)
        for l in sorted(subway_lines)
    ]
    if bus_routes is not None:
        rows += [
            ("BUS", r.노선_ID, r.노선_명칭, r.노선_유형)
            for r in bus_routes.itertuples()
        ]
    with conn.cursor() as cur:
        cur.execute(
            "CREATE TEMP TABLE _route (mode text, source_id text, name text, "
            "route_type text) ON COMMIT DROP"
        )
        copy_rows(cur, "_route", ["mode", "source_id", "name", "route_type"], rows)
        cur.execute(
            """
            INSERT INTO transit_route (mode, source_id, name, route_type)
            SELECT mode, source_id, name, route_type FROM _route
            ON CONFLICT (mode, source_id) DO UPDATE
                SET name = EXCLUDED.name, route_type = EXCLUDED.route_type
            """
        )
    return len(rows)


def load_bus_headways(headway_path, route_id_path, weektag: str) -> dict[str, int]:
    """노선_ID → 배차간격(초).

    배차 파일의 키는 `노선번호`('0017') 이고 구간 데이터의 키는 `노선_ID`('100100124') 다.
    노선ID정보가 둘을 잇는다 — 이 매핑이 없으면 배차를 붙일 수 없다.
    """
    sheet = BUS_HEADWAY_SHEET[weektag]
    hw = pd.read_excel(headway_path, sheet_name=sheet, dtype={"노선번호": str})
    idm = pd.read_excel(route_id_path, dtype={"노선명": str, "ROUTEID": str})

    merged = hw.merge(idm, left_on="노선번호", right_on="노선명", how="inner")
    merged["sec"] = pd.to_numeric(merged["배차간격"], errors="coerce") * 60
    ok = merged.dropna(subset=["sec"])
    return {str(r.ROUTEID): int(r.sec) for r in ok.itertuples() if r.sec > 0}


def split_unpriced_routes(
    sections: pd.DataFrame, headways: dict[str, int]
) -> tuple[pd.DataFrame, list[str]]:
    """배차를 모르는 노선을 구간에서 떼어낸다. `(남길 구간, 뺀 노선_ID 목록)`.

    대기를 매길 수 없는 노선은 그래프에 넣지 않는다(BOARD_FALLBACK_SEC 를 지운 이유 참조).
    지금 데이터에서는 새벽 자율주행 4개 노선이다 — 배차간격 0, 하루 1회(03:30).
    """
    has = sections["노선_ID"].isin(headways.keys())
    dropped = sorted(sections.loc[~has, "노선_ID"].unique())
    return sections[has].reset_index(drop=True), dropped


#: 원본 정류장마스터의 좌표가 틀린 정류장. `정류장_ID → (경도, 위도)`.
#:
#: 노선의 앞뒤 정류장과 1~15km 어긋나 있어, 그 구간을 지나면 직선거리 기준 148~1,155km/h 가
#: 나왔다. 좌표가 틀리면 빠른 구간만 생기는 것이 아니라 **엉뚱한 자리에서 그 노선을 탈 수 있게**
#: 된다. 원본이 고쳐지면 이 목록에서 빼면 된다.
BUS_STOP_COORD_FIX: dict[str, tuple[float, float]] = {
    "113900266": (126.9176417, 37.5672426),   # 사천고가앞 (ARS 14716) · 마포06 — 원본은 14.8km 밖
    "113900038": (126.9475662, 37.5386883),   # S마트 (ARS 14541) · 마포01·02 — 원본은 1.0km 밖
    "120900051": (126.9260000, 37.4611000),   # 삼성산주공정문 (ARS 21539) · 관악08 — 원본은 2.0km 밖
}


def apply_stop_coord_fix(
    master: pd.DataFrame, fixes: dict[str, tuple[float, float]] = BUS_STOP_COORD_FIX
) -> tuple[pd.DataFrame, int, list[str]]:
    """정류장마스터의 좌표를 보정한다. `(보정한 마스터, 보정 수, 원본에 없는 ID)`.

    원본에 없는 ID 는 정류장이 사라졌거나 번호가 바뀐 것이다 — 낡은 목록을 들고 있지 않도록
    부르는 쪽이 경고한다.
    """
    out = master.copy()
    ids = set(out["정류장_ID"])
    stale = sorted(k for k in fixes if k not in ids)
    for stop_id, (lng, lat) in fixes.items():
        hit = out["정류장_ID"] == stop_id
        out.loc[hit, "lng"] = lng
        out.loc[hit, "lat"] = lat
    return out, len(fixes) - len(stale), stale


def insert_bus_nodes_edges(
    conn, build_id: int, sections: pd.DataFrame, headways: dict[str, int]
) -> dict[str, int]:
    """버스 정류장·노선을 그래프에 넣는다. 지하철과 같은 구조를 쓴다.

    지하철과 다른 점이 둘 있다.

    1. **승강장 ↔ 대합실 이동이 없다.** 버스 정류장은 곧 길가라 지하로 내려갈 일이 없다.
       BOARD 는 대기만, ALIGHT 는 1초다.
    2. **환승 엣지를 따로 두지 않는다.** 같은 정류장에서 다른 노선으로 갈아타는 것은
       실제로 그냥 기다리는 일이라 ALIGHT → BOARD 가 공짜인 것이 맞다.
       지하철에서 이것이 버그였던 이유는 환승 통로를 걷는 비용이 있었기 때문이다.
       버스 ↔ 지하철 환승은 보행망을 통해 자연스럽게 이어진다.
    """
    hour_cols = [f"운행시간_{h:02d}시" for h in range(24)]
    stats = {}

    with conn.cursor() as cur:
        # 정류장 — 좌표가 있는 것만. 없으면 그래프에 붙일 자리가 없다.
        master = pd.read_csv(BUS_STOP_PATH, encoding="cp949", dtype=str)
        master["lat"] = pd.to_numeric(master["위도"], errors="coerce")
        master["lng"] = pd.to_numeric(master["경도"], errors="coerce")
        master, stats["coord_fixed"], stats["coord_fix_stale"] = apply_stop_coord_fix(master)
        master = master.dropna(subset=["lat", "lng"])

        used = set(sections["출발_정류장_ID"]) | set(sections["도착_정류장_ID"])
        keep = master[master["정류장_ID"].isin(used)]

        cur.execute("CREATE TEMP TABLE _bs (sid text, name text, lng float8, lat float8) ON COMMIT DROP")
        copy_rows(
            cur, "_bs", ["sid", "name", "lng", "lat"],
            ((r.정류장_ID, r.정류장_명칭, r.lng, r.lat) for r in keep.itertuples()),
        )
        cur.execute(
            """
            INSERT INTO transit_stop (mode, source_id, name, geom)
            SELECT 'BUS', sid, name, ST_Transform(ST_SetSRID(ST_MakePoint(lng, lat), 4326), %s)
            FROM _bs
            -- transit_stop 은 빌드를 넘어 남는다. DO NOTHING 이면 원본에서 이름·좌표가 바뀌어도
            -- 처음 들어간 값이 영영 남는다 — 좌표 보정(BUS_STOP_COORD_FIX)도 먹지 않았다.
            ON CONFLICT (mode, source_id) DO UPDATE
                SET name = EXCLUDED.name, geom = EXCLUDED.geom
            """,
            (TARGET_EPSG,),
        )
        cur.execute("SELECT source_id, id FROM transit_stop WHERE mode = 'BUS'")
        stop_ids = dict(cur.fetchall())

        # 좌표 있는 정류장만 남긴 구간
        valid = sections[
            sections["출발_정류장_ID"].isin(stop_ids) & sections["도착_정류장_ID"].isin(stop_ids)
        ].copy()
        stats["sections"] = len(valid)
        stats["sections_dropped"] = len(sections) - len(valid)

        # STOP 노드
        cur.execute(
            """
            INSERT INTO graph_node (build_id, kind, source_id, stop_id, geom)
            SELECT %s, 'STOP', s.source_id, s.id, s.geom
            FROM transit_stop s WHERE s.mode = 'BUS'
            """,
            (build_id,),
        )
        cur.execute(
            """
            SELECT n.source_id, n.id FROM graph_node n
            JOIN transit_stop s ON s.id = n.stop_id
            WHERE n.build_id = %s AND n.kind = 'STOP' AND s.mode = 'BUS'
            """,
            (build_id,),
        )
        stop_node = dict(cur.fetchall())

        # PLATFORM 노드 — (정류장 × 노선)
        pairs = set(zip(valid["노선_ID"], valid["출발_정류장_ID"])) | set(
            zip(valid["노선_ID"], valid["도착_정류장_ID"])
        )
        cur.execute("CREATE TEMP TABLE _bp (sid text, line text) ON COMMIT DROP")
        copy_rows(cur, "_bp", ["sid", "line"], ((sid, line) for line, sid in pairs))
        cur.execute(
            """
            INSERT INTO graph_node (build_id, kind, source_id, line, stop_id, geom)
            SELECT %s, 'PLATFORM', p.sid, p.line, s.id, s.geom
            FROM _bp p JOIN transit_stop s ON s.mode = 'BUS' AND s.source_id = p.sid
            """,
            (build_id,),
        )
        cur.execute(
            """
            SELECT n.source_id, n.line, n.id FROM graph_node n
            JOIN transit_stop s ON s.id = n.stop_id
            WHERE n.build_id = %s AND n.kind = 'PLATFORM' AND s.mode = 'BUS'
            """,
            (build_id,),
        )
        platform = {(sid, line): nid for sid, line, nid in cur.fetchall()}
        stats["stops"] = len(stop_node)
        stats["platforms"] = len(platform)

        # RIDE — 시간대별 운행시간을 배열로. 운행 없는 시간대는 NO_SERVICE 로 남긴다.
        hours = valid[hour_cols].to_numpy()

        def ride_rows():
            for i, r in enumerate(valid.itertuples()):
                a = platform.get((r.출발_정류장_ID, r.노선_ID))
                b = platform.get((r.도착_정류장_ID, r.노선_ID))
                if a is None or b is None or a == b:
                    continue
                arr = [
                    NO_SERVICE if v == NO_SERVICE
                    else int(min(max(v, BUS_RUN_MIN_SEC), BUS_RUN_MAX_SEC))
                    for v in hours[i]
                ]
                med = representative(arr)
                if med == NO_SERVICE:
                    continue  # 어느 시간대에도 과반 운행하지 않는 구간
                yield (build_id, a, b, "RIDE", med, hourly_literal(arr), r.노선_ID)

        stats["ride"] = copy_rows(
            cur, "graph_edge",
            ["build_id", "from_node_id", "to_node_id", "kind", "weight_sec", "weight_by_hour", "line"],
            ride_rows(),
        )

        # BOARD / ALIGHT — 정류장이 곧 길가라 대합실 이동이 없다.
        # 배차 없는 노선은 split_unpriced_routes 에서 이미 뺐다.
        rows_b, rows_a = [], []
        for (sid, line), pid in platform.items():
            nid = stop_node.get(sid)
            if nid is None:
                continue
            wait = max(1, round(headways[line] / 2))
            rows_b.append((build_id, nid, pid, "BOARD", wait, line))
            rows_a.append((build_id, pid, nid, "ALIGHT", 1, line))

        stats["board"] = copy_rows(
            cur, "graph_edge",
            ["build_id", "from_node_id", "to_node_id", "kind", "weight_sec", "line"], rows_b,
        )
        stats["alight"] = copy_rows(
            cur, "graph_edge",
            ["build_id", "from_node_id", "to_node_id", "kind", "weight_sec", "line"], rows_a,
        )

    return stats


def insert_access_edges(conn, build_id: int) -> tuple[int, list[float], int]:
    """역 ↔ 가장 가까운 보행 노드. `<->` 로 GiST KNN 을 탄다.

    16.5만 개 중에서 찾아야 하므로 순차 탐색으로는 못 한다.
    `(엣지 수, 스냅 거리 목록, 너무 멀어 건너뛴 역 수)` 를 돌려준다.

    **거리가 `ACCESS_MAX_M` 을 넘으면 엣지를 만들지 않는다.** 보행망은 서울시 경계인데
    시각표는 양주·인천·신창까지 담고 있어, 서울 밖 역은 수십 km 떨어진 노드에 붙는다.
    그대로 두면 "73km 를 걸어 역에 간다"는 경로가 만들어진다.
    그런 역은 지하철로 지나갈 수는 있어도 걸어서 드나들 수는 없는 역이 되며,
    서울 안의 모임 장소를 찾는 이 서비스에서는 문제가 되지 않는다.
    """
    with conn.cursor() as cur:
        # 방금 COPY 로 넣은 17만 노드를 플래너가 보게 한다. 트랜잭션 안이라
        # autoanalyze 가 손대지 못하고, 통계가 비어 있으면 아래 조인이 인덱스를 버리고
        # 순차 탐색으로 풀려 끝나지 않는다 (정류장 11,400개에서 8분 넘게 진행 중이라 취소).
        cur.execute("ANALYZE graph_node")

        # 후보를 ST_DWithin 으로 먼저 좁힌다. `<->` KNN 만으로는 build_id·kind 조건과
        # 함께 쓰기 어렵고, 어차피 ACCESS_MAX_M 밖은 버리므로 반경으로 거르는 것이
        # 의미상으로도 같다.
        cur.execute(
            """
            WITH snap AS (
                SELECT DISTINCT ON (s.id)
                       s.id AS stop_node, w.id AS walk_node,
                       ST_Distance(s.geom, w.geom) AS d
                FROM graph_node s
                JOIN graph_node w
                  ON w.build_id = %(b)s AND w.kind = 'WALK'
                 AND ST_DWithin(w.geom, s.geom, %(maxd)s)
                WHERE s.build_id = %(b)s AND s.kind = 'STOP'
                ORDER BY s.id, s.geom <-> w.geom
            ), near AS (
                SELECT * FROM snap WHERE d <= %(maxd)s
            ), ins AS (
                INSERT INTO graph_edge
                    (build_id, from_node_id, to_node_id, kind, weight_sec, distance_m)
                SELECT %(b)s, walk_node, stop_node, 'ACCESS',
                       greatest(1, round(d / %(speed)s)), d FROM near
                UNION ALL
                SELECT %(b)s, stop_node, walk_node, 'ACCESS',
                       greatest(1, round(d / %(speed)s)), d FROM near
            )
            SELECT d, (d <= %(maxd)s) AS kept FROM snap
            """,
            {"b": build_id, "speed": WALK_SPEED_MPS, "maxd": ACCESS_MAX_M},
        )
        rows = cur.fetchall()
    kept = [float(d) for d, ok in rows if ok]
    skipped = sum(1 for _, ok in rows if not ok)
    return len(kept) * 2, kept, skipped


#: 빌드 규모를 그래프 테이블에서 직접 센다. 종류 × 수단. 엣지의 수단은 도착 노드가 걸린
#: 정류장의 수단이다(역에서 보행망으로 나오는 ACCESS 는 WALK). V11 마이그레이션과 같은 쿼리다.
COUNT_SQL = """
    SELECT 'NODE' AS what, n.kind, coalesce(s.mode, 'WALK') AS mode, count(*)
    FROM graph_node n LEFT JOIN transit_stop s ON s.id = n.stop_id
    WHERE n.build_id = %(id)s
    GROUP BY n.kind, s.mode
    UNION ALL
    SELECT 'EDGE', e.kind, coalesce(s.mode, 'WALK'), count(*)
    FROM graph_edge e
    JOIN graph_node t ON t.id = e.to_node_id
    LEFT JOIN transit_stop s ON s.id = t.stop_id
    WHERE e.build_id = %(id)s
    GROUP BY e.kind, s.mode
"""

#: 들어오는 엣지도 나가는 엣지도 없는 노드. 0 이 아니면 스냅이나 적재가 어딘가 끊긴 것이다.
ISOLATED_SQL = """
    SELECT count(*) FROM graph_node n
    WHERE n.build_id = %(id)s
      AND NOT EXISTS (SELECT 1 FROM graph_edge e WHERE e.from_node_id = n.id)
      AND NOT EXISTS (SELECT 1 FROM graph_edge e WHERE e.to_node_id = n.id)
"""


def summarize_counts(rows) -> dict:
    """`(what, kind, mode, count)` 행 → graph_build 합계 칸. 수단을 가리지 않고 더한다."""
    total = {}
    for what, kind, _mode, n in rows:
        total[(what, kind)] = total.get((what, kind), 0) + int(n)
    col = lambda what, kind: total.get((what, kind), 0)  # noqa: E731
    return {
        "nodes_walk": col("NODE", "WALK"),
        "nodes_stop": col("NODE", "STOP"),
        "nodes_platform": col("NODE", "PLATFORM"),
        "edges_walk": col("EDGE", "WALK"),
        "edges_access": col("EDGE", "ACCESS"),
        "edges_board": col("EDGE", "BOARD"),
        "edges_alight": col("EDGE", "ALIGHT"),
        "edges_ride": col("EDGE", "RIDE"),
        "edges_transfer": col("EDGE", "TRANSFER"),
    }


def finish_build(conn, build_id: int, fallback_ratio: float, duration_ms: int) -> dict:
    """빌드를 끝낸다. 규모는 **그래프 테이블에서 직접 센다** — 적재 단계가 돌려준 숫자를
    손으로 더하지 않는다. 그렇게 더하다 버스를 넣을 때 노드 쪽이 빠져 정류장 402 · 플랫폼 562
    (지하철만)로 기록돼 있었다. 센 결과를 돌려준다.
    """
    with conn.cursor() as cur:
        cur.execute(COUNT_SQL, {"id": build_id})
        rows = cur.fetchall()
        cur.execute(ISOLATED_SQL, {"id": build_id})
        isolated = cur.fetchone()[0]
        totals = summarize_counts(rows)
        counts = [
            {"what": w, "kind": k, "mode": m, "count": int(n)}
            for w, k, m, n in sorted(rows, key=lambda r: (r[0] != "NODE", r[1], r[2]))
        ]
        cur.execute(
            """
            UPDATE graph_build SET
                status = 'SUCCESS',
                -- now() 는 트랜잭션 시작 시각이라 빌드 맨 앞이 찍힌다. 지금 이 순간을 쓴다.
                finished_at = clock_timestamp(), duration_ms = %(dur)s,
                nodes_walk = %(nodes_walk)s, nodes_stop = %(nodes_stop)s,
                nodes_platform = %(nodes_platform)s,
                edges_walk = %(edges_walk)s, edges_access = %(edges_access)s,
                edges_board = %(edges_board)s, edges_alight = %(edges_alight)s,
                edges_ride = %(edges_ride)s, edges_transfer = %(edges_transfer)s,
                counts = %(counts)s::jsonb,
                isolated_nodes = %(iso)s, transfer_fallback_ratio = %(fb)s
            WHERE id = %(id)s
            """,
            {"id": build_id, "dur": duration_ms, "iso": isolated, "fb": fallback_ratio,
             "counts": json.dumps(counts), **totals},
        )
        # 활성 전환은 마지막에. 부분 유니크 인덱스가 둘을 허용하지 않으므로 먼저 내린다.
        cur.execute("UPDATE graph_build SET is_active = FALSE WHERE is_active")
        cur.execute("UPDATE graph_build SET is_active = TRUE WHERE id = %s", (build_id,))
    return {**totals, "isolated_nodes": isolated}


# ──────────────────────────────────────────────────────────────────────────────
# 오케스트레이션
# ──────────────────────────────────────────────────────────────────────────────


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="보행망 + 지하철 그래프 빌드")
    parser.add_argument("--graphml", default=GRAPHML_PATH)
    parser.add_argument("--timetable", default=TIMETABLE_PATH)
    parser.add_argument("--station-master", default=STATION_MASTER_PATH)
    parser.add_argument("--transfers", default=TRANSFER_PATH)
    parser.add_argument("--measured-transfers", default=MEASURED_TRANSFER_PATH)
    parser.add_argument("--weektag", default="DAY")
    parser.add_argument("--area", default="Seoul, South Korea")
    parser.add_argument("--bus-sections", default=BUS_SECTION_PATH)
    parser.add_argument("--bus-routes", default=BUS_ROUTE_PATH)
    parser.add_argument("--bus-headways", default=BUS_HEADWAY_PATH)
    parser.add_argument("--bus-route-ids", default=BUS_ROUTE_ID_PATH)
    parser.add_argument("--no-bus", action="store_true", help="지하철만 빌드한다")
    parser.add_argument(
        "--reset", action="store_true",
        help="기존 빌드를 전부 버리고 처음부터. 스키마를 고쳤을 때 쓴다",
    )
    args = parser.parse_args(argv)

    needed = [args.graphml, args.timetable, args.station_master, args.transfers,
              args.measured_transfers]
    if not args.no_bus:
        needed += [args.bus_sections, args.bus_routes, args.bus_headways, args.bus_route_ids,
                   BUS_STOP_PATH]
    for p in needed:
        if not Path(p).exists():
            print(f"파일 없음: {p}", file=sys.stderr)
            return 2

    t0 = time.monotonic()
    print("시각표 읽는 중…", flush=True)
    tt = load_timetable(args.timetable, args.weektag)
    legs = inter_station_times(tt)
    waits = board_weights_by_hour(headways_by_hour(tt), subway_service_hours(tt))
    transfers, offset, overlap = calibrate_transfers(
        load_measured_transfers(args.measured_transfers), load_transfers(args.transfers)
    )
    n_cal = int((transfers["source"] == "CALIBRATED").sum())
    print(f"  환승 측정 {len(transfers) - n_cal}쌍 · 보정 {n_cal}쌍 "
          f"(거리÷1.2 에 +{offset}초 — 두 파일이 겹치는 {overlap}쌍의 중앙값)", flush=True)

    # 급행은 별도 노선이므로 급행이 서는 역에만 급행 플랫폼이 생긴다.
    raw_stations = (
        tt[["line_key", "SI_ID", "STATION_NM"]].drop_duplicates()
        .rename(columns={"line_key": "line", "SI_ID": "si_id", "STATION_NM": "station"})
    )
    stations, missing = join_station_coords(
        raw_stations, pd.read_csv(args.station_master, encoding="cp949", dtype=str)
    )
    express_lines = sorted({l for l in stations["line"].unique() if is_express(l)})
    print(f"  역 {len(stations)}개 (좌표 미매칭 {len(missing)} 제외) · "
          f"구간 {len(legs)} · 환승쌍 {len(transfers)}", flush=True)
    if express_lines:
        n_ex = int(stations["line"].map(is_express).sum())
        print(f"  급행 분리: {', '.join(express_lines)} · 급행 정차 {n_ex}개", flush=True)

    with connect() as conn:
        if args.reset:
            truncate_all(conn)
            conn.commit()
            print("기존 빌드 전부 비움", flush=True)

        build_id = start_build(conn, args.area)
        conn.commit()
        print(f"빌드 #{build_id}", flush=True)

        try:
            stop_ids = load_stations(conn, stations)
            print(f"  transit_stop {len(stop_ids)}", flush=True)

            print("  보행망 적재 중… (191MB graphml)", flush=True)
            nw, ew = insert_walk_nodes_edges(conn, build_id, args.graphml)
            print(f"  WALK 노드 {nw:,} · 엣지 {ew:,}", flush=True)

            stop_node, platform = insert_transit_nodes(conn, build_id, stations, stop_ids)
            print(f"  STOP {len(stop_node)} · PLATFORM {len(platform)}", flush=True)

            traverse, traverse_default = station_traverse_seconds(transfers)
            print(f"  승강장↔대합실 {len(traverse)}역은 그 역 환승값에서 · 나머지 {traverse_default}초", flush=True)
            per_platform = platform_traverse_seconds(transfers, traverse, traverse_default)
            raised = {k: v - traverse.get(k[0], traverse_default) for k, v in per_platform.items()}
            ties = sum(1 for d in raised.values() if d == 1)
            big = sorted(((k, d) for k, d in raised.items() if d > 1), key=lambda kd: -kd[1])
            print(f"  대합실 경유가 환승보다 싸지 않게 올린 승강장 {ties + len(big)}개 "
                  f"(동점 깨기 +1초 {ties}개) "
                  + ", ".join(f"{k[0]}·{k[1]} +{d}" for k, d in big), flush=True)

            er = insert_ride_edges(conn, build_id, legs, platform)
            eb, ealight, unpriced = insert_board_alight_edges(
                conn, build_id, platform, stop_node, waits, traverse, traverse_default, per_platform
            )
            et, fallback = insert_transfer_edges(
                conn, build_id, stations, platform, transfers, waits
            )
            print(f"  RIDE {er:,} · BOARD {eb:,} · ALIGHT {ealight:,} · TRANSFER {et:,}", flush=True)
            if unpriced:
                print(f"  ⚠ 배차를 몰라 승차를 막은 승강장 {unpriced}개", flush=True)

            bus_routes = None
            if not args.no_bus:
                print("  버스 적재 중…", flush=True)
                bus_routes = load_bus_routes(args.bus_routes)
                sections = load_bus_sections(args.bus_sections, bus_routes, args.weektag)
                bus_headways = load_bus_headways(
                    args.bus_headways, args.bus_route_ids, args.weektag
                )
                sections, unpriced_routes = split_unpriced_routes(sections, bus_headways)
                bs = insert_bus_nodes_edges(conn, build_id, sections, bus_headways)
                print(f"  버스 정류장 {bs['stops']:,} · 플랫폼 {bs['platforms']:,} · "
                      f"RIDE {bs['ride']:,} · BOARD {bs['board']:,}", flush=True)
                print(f"    구간 {bs['sections']:,} (좌표 없어 제외 {bs['sections_dropped']:,}) · "
                      f"배차 없어 뺀 노선 {len(unpriced_routes)} · "
                      f"좌표 보정 {bs['coord_fixed']}개", flush=True)
                if bs["coord_fix_stale"]:
                    print(f"    ⚠ 좌표 보정 목록에 있는데 원본에 없는 정류장: "
                          f"{', '.join(bs['coord_fix_stale'])} — BUS_STOP_COORD_FIX 를 확인할 것", flush=True)
                if unpriced_routes:
                    names = bus_routes.set_index("노선_ID")["노선_명칭"]
                    print("      " + ", ".join(names.get(r, r) for r in unpriced_routes), flush=True)
                er += bs["ride"]
                eb += bs["board"]
                ealight += bs["alight"]

            n_routes = insert_transit_routes(conn, bus_routes, set(stations["line"]))
            print(f"  transit_route {n_routes:,}", flush=True)

            # 버스 정류장까지 만든 뒤에 스냅한다 — 역과 정류장을 한 번에 붙인다.
            print("  ACCESS 스냅 중…", flush=True)
            ea, snap, skipped = insert_access_edges(conn, build_id)
            s = summarize_snap(snap)
            print(f"  ACCESS {ea:,} · 스냅 중앙 {s['median_m']}m · p95 {s['p95_m']}m · "
                  f"최대 {s['max_m']}m", flush=True)
            if skipped:
                print(f"  보행 진출입 없는 역 {skipped}개 — {ACCESS_MAX_M:.0f}m 밖. "
                      f"보행망(서울)을 벗어난 구간이며 지하철로 지날 수만 있다", flush=True)
            if s["over_warn"]:
                print(f"  ⚠ {SNAP_WARN_M:.0f}m 초과 {s['over_warn']}개 — "
                      f"보행망에 출입구가 안 잡힌 역이다", flush=True)

            counted = finish_build(
                conn, build_id,
                round(fallback / et, 4) if et else 0.0,
                int((time.monotonic() - t0) * 1000),
            )
            conn.commit()
            if counted["isolated_nodes"]:
                print(f"  ⚠ 고립 노드 {counted['isolated_nodes']:,}개 — 엣지가 하나도 없다", flush=True)

        except Exception as e:  # noqa: BLE001 — 실패해도 회차는 남긴다
            conn.rollback()
            with conn.cursor() as cur:
                cur.execute(
                    "UPDATE graph_build SET status='FAILED', finished_at=now(), error_message=%s WHERE id=%s",
                    (str(e)[:2000], build_id),
                )
            conn.commit()
            print(f"  → FAILED: {e}", file=sys.stderr)
            raise

        # 정리는 빌드가 끝난 **뒤**의 일이다. 여기서 실패해도 방금 만든 그래프는 멀쩡하므로
        # FAILED 로 표시하지 않는다 — 실제로 오래된 빌드를 지우다 막혀 완성된 빌드가
        # FAILED + is_active 라는 모순된 상태로 남은 적이 있다.
        # 못 지운 빌드는 디스크만 더 쓸 뿐 다음 빌드가 다시 지운다.
        try:
            dropped = prune_old_builds(conn, build_id)
            conn.commit()
            if dropped:
                print(f"  이전 빌드 {dropped}개 정리", flush=True)
        except Exception as e:  # noqa: BLE001
            conn.rollback()
            print(f"  ⚠ 이전 빌드 정리 실패 (그래프는 정상): {e}", file=sys.stderr)

        with conn.cursor() as cur:
            cur.execute(
                "SELECT kind, count(*) FROM graph_edge WHERE build_id=%s GROUP BY kind ORDER BY 2 DESC",
                (build_id,),
            )
            print(f"\n빌드 #{build_id} 완료  {(time.monotonic() - t0):.0f}초")
            for kind, n in cur.fetchall():
                print(f"  {kind:<9} {n:>9,}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
