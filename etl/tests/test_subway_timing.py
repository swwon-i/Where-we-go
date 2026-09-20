"""subway_timing 테스트 — 원본 CSV 없이 돌아간다."""

from __future__ import annotations

import numpy as np
import pandas as pd
import pytest

from etl.subway_timing import (
    HEADWAY_MAX_SEC,
    LINE_ALIAS,
    REFERENCE_HEADWAY_MIN,
    REFERENCE_MAX_RATIO,
    REFERENCE_SCOPE_STATIONS,
    RUN_MAX_SEC,
    check_against_reference,
    headways,
    inter_station_times,
    join_station_coords,
    normalize_station,
    parse_hms,
)


def frame(rows: list[dict]) -> pd.DataFrame:
    """시각표 모양의 최소 DataFrame. 시각은 `HH:MM:SS` 로 준다."""
    df = pd.DataFrame(rows)
    df["arrive_sec"] = df["STT"].map(parse_hms)
    df["depart_sec"] = df.get("EDT", df["STT"]).map(parse_hms)
    return df


class TestParseHms:
    @pytest.mark.parametrize(
        "raw,expected",
        [("00:00:00", 0), ("01:02:03", 3723), ("12:53:30", 46_410), ("23:59:59", 86_399)],
    )
    def test_normal(self, raw, expected):
        assert parse_hms(raw) == expected

    def test_past_midnight(self):
        """영업일 기준이라 24시를 넘는 값이 실제로 있다. 원본 최대가 25:14:00 이다."""
        assert parse_hms("24:00:00") == 86_400
        assert parse_hms("25:14:00") == 90_840

    def test_past_midnight_orders_after_late_night(self):
        """자정 넘김이 앞 시각보다 커야 배차 계산이 음수로 뒤집히지 않는다."""
        assert parse_hms("25:14:00") > parse_hms("23:50:00")

    @pytest.mark.parametrize("raw", [None, "", "  ", float("nan"), "12:30", "ab:cd:ef"])
    def test_invalid(self, raw):
        assert np.isnan(parse_hms(raw))


class TestNormalizeStation:
    def test_strips_parenthetical(self):
        assert normalize_station("자양(뚝섬한강공원)") == "자양"

    def test_strips_spaces(self):
        assert normalize_station(" 서울 역 ") == "서울역"


class TestInterStationTimes:
    def test_consecutive_stops_of_same_train(self):
        legs = inter_station_times(frame([
            {"LINE": "2", "INOUTTAG": "UP", "TRAIN_NO": "T1", "SI_ID": "A",
             "STATION_NM": "강남", "STT": "19:00:00", "EDT": "19:00:30"},
            {"LINE": "2", "INOUTTAG": "UP", "TRAIN_NO": "T1", "SI_ID": "B",
             "STATION_NM": "역삼", "STT": "19:02:00", "EDT": "19:02:30"},
        ]))
        assert len(legs) == 1
        assert legs.iloc[0]["run_sec"] == 90  # 19:02:00 - 19:00:30
        assert legs.iloc[0]["from_station"] == "강남"
        assert legs.iloc[0]["to_station"] == "역삼"

    def test_does_not_link_across_trains(self):
        """다른 열차의 정차를 이으면 역간 시간이 엉뚱해진다."""
        legs = inter_station_times(frame([
            {"LINE": "2", "INOUTTAG": "UP", "TRAIN_NO": "T1", "SI_ID": "A",
             "STATION_NM": "강남", "STT": "19:00:00", "EDT": "19:00:30"},
            {"LINE": "2", "INOUTTAG": "UP", "TRAIN_NO": "T2", "SI_ID": "B",
             "STATION_NM": "역삼", "STT": "19:02:00", "EDT": "19:02:30"},
        ]))
        assert legs.empty

    def test_does_not_link_across_directions(self):
        legs = inter_station_times(frame([
            {"LINE": "2", "INOUTTAG": "UP", "TRAIN_NO": "T1", "SI_ID": "A",
             "STATION_NM": "강남", "STT": "19:00:00", "EDT": "19:00:30"},
            {"LINE": "2", "INOUTTAG": "DOWN", "TRAIN_NO": "T1", "SI_ID": "B",
             "STATION_NM": "역삼", "STT": "19:02:00", "EDT": "19:02:30"},
        ]))
        assert legs.empty

    def test_drops_implausible_gap(self):
        """회차·주박 구간은 역간 주행이 아니다."""
        legs = inter_station_times(frame([
            {"LINE": "2", "INOUTTAG": "UP", "TRAIN_NO": "T1", "SI_ID": "A",
             "STATION_NM": "강남", "STT": "05:00:00", "EDT": "05:00:30"},
            {"LINE": "2", "INOUTTAG": "UP", "TRAIN_NO": "T1", "SI_ID": "B",
             "STATION_NM": "역삼", "STT": "23:00:00", "EDT": "23:00:30"},
        ]))
        assert legs.empty
        assert RUN_MAX_SEC <= 1_800

    def test_median_over_repeated_runs(self):
        rows = []
        for i, gap in enumerate([80, 90, 100]):
            rows += [
                {"LINE": "2", "INOUTTAG": "UP", "TRAIN_NO": f"T{i}", "SI_ID": "A",
                 "STATION_NM": "강남", "STT": f"1{i}:00:00", "EDT": f"1{i}:00:00"},
                {"LINE": "2", "INOUTTAG": "UP", "TRAIN_NO": f"T{i}", "SI_ID": "B",
                 "STATION_NM": "역삼", "STT": f"1{i}:0{gap // 60}:{gap % 60:02d}",
                 "EDT": f"1{i}:0{gap // 60}:{gap % 60:02d}"},
            ]
        legs = inter_station_times(frame(rows))
        assert legs.iloc[0]["run_sec"] == 90
        assert legs.iloc[0]["samples"] == 3


class TestHeadways:
    @pytest.fixture
    def branching_line(self):
        """같은 역·같은 방향에 4분 간격으로 들어오되 종착지가 번갈아 다른 노선.

        1호선 하행에 인천행과 신창행이 섞여 들어오는 상황이다.
        """
        rows = []
        for i, dest in enumerate(["인천", "신창", "인천", "신창", "인천"]):
            rows.append({
                "LINE": "1", "INOUTTAG": "DOWN", "TRAIN_NO": f"T{i}", "SI_ID": "S1",
                "STATION_NM": "시청", "GUBHANG": "0", "ED_STT_NM": dest,
                "STT": f"08:{i * 4:02d}:00", "EDT": f"08:{i * 4:02d}:30",
            })
        return frame(rows)

    def test_groups_by_station_and_direction(self, branching_line):
        result = headways(branching_line, hour=8)
        assert len(result) == 1
        assert result.iloc[0]["headway_sec"] == 240  # 4분

    def test_does_not_split_by_destination(self, branching_line):
        """종착지까지 쪼개면 배차가 부풀려진다.

        실측: 1호선 8시대가 종착지별로는 13.0분, (역, 방향)만으로는 4.5분,
        운행현황 참조값은 3분이었다. 시청역 가는 승객은 아무 하행이나 타므로
        (역, 방향) 이 맞다. 이 테스트는 그 선택을 고정한다.
        """
        correct = headways(branching_line, hour=8).iloc[0]["headway_sec"]

        split = branching_line.sort_values(["SI_ID", "INOUTTAG", "ED_STT_NM", "arrive_sec"])
        gaps = split.groupby(["SI_ID", "INOUTTAG", "ED_STT_NM"])["arrive_sec"].diff().dropna()

        assert correct == 240
        assert gaps.median() == 480  # 종착지별로 보면 8분 — 2배로 부풀려진다
        assert gaps.median() > correct

    def test_express_excluded_by_default(self):
        rows = [
            {"LINE": "9", "INOUTTAG": "UP", "TRAIN_NO": "L1", "SI_ID": "S", "STATION_NM": "여의도",
             "GUBHANG": "0", "ED_STT_NM": "종합운동장", "STT": "08:00:00", "EDT": "08:00:30"},
            {"LINE": "9", "INOUTTAG": "UP", "TRAIN_NO": "X1", "SI_ID": "S", "STATION_NM": "여의도",
             "GUBHANG": "1", "ED_STT_NM": "종합운동장", "STT": "08:03:00", "EDT": "08:03:30"},
            {"LINE": "9", "INOUTTAG": "UP", "TRAIN_NO": "L2", "SI_ID": "S", "STATION_NM": "여의도",
             "GUBHANG": "0", "ED_STT_NM": "종합운동장", "STT": "08:06:00", "EDT": "08:06:30"},
        ]
        local = headways(frame(rows), hour=8).iloc[0]["headway_sec"]
        mixed = headways(frame(rows), hour=8, local_only=False).iloc[0]["headway_sec"]
        assert local == 360   # 완행끼리 6분
        assert mixed == 180   # 급행을 섞으면 3분으로 짧아 보인다

    def test_hour_filter(self, branching_line):
        assert headways(branching_line, hour=9).empty

    def test_drops_gaps_beyond_an_hour(self):
        rows = [
            {"LINE": "2", "INOUTTAG": "UP", "TRAIN_NO": "T1", "SI_ID": "S", "STATION_NM": "A",
             "GUBHANG": "0", "ED_STT_NM": "X", "STT": "05:00:00", "EDT": "05:00:00"},
            {"LINE": "2", "INOUTTAG": "UP", "TRAIN_NO": "T2", "SI_ID": "S", "STATION_NM": "A",
             "GUBHANG": "0", "ED_STT_NM": "X", "STT": "05:59:59", "EDT": "05:59:59"},
        ]
        assert headways(frame(rows), hour=5).empty or HEADWAY_MAX_SEC == 3_600


class TestReferenceCheck:
    def test_scoped_line_uses_core_stations_only(self):
        """1호선은 공사 구간만 비교한다. 전 구간으로 재면 외곽 지선 때문에 2.67배가 된다."""
        by_hour = pd.DataFrame([
            {"hour": 8, "line": "1", "station": "시청", "headway_sec": 210},    # 3.5분
            {"hour": 8, "line": "1", "station": "동두천", "headway_sec": 1_800},  # 30분
        ])
        row = check_against_reference(by_hour).iloc[0]
        assert row["computed_min"] == 3.5
        assert row["scoped"]
        assert row["ok"]

    def test_unscoped_line_uses_all_stations(self):
        by_hour = pd.DataFrame([
            {"hour": 8, "line": "2", "station": "강남", "headway_sec": 180},
        ])
        row = check_against_reference(by_hour).iloc[0]
        assert row["computed_min"] == 3.0
        assert not row["scoped"]

    def test_flags_inflated_value(self):
        """그룹 기준을 잘못 잡아 배차가 부풀려지면 여기서 걸려야 한다."""
        by_hour = pd.DataFrame([
            {"hour": 8, "line": "2", "station": "강남", "headway_sec": 780},  # 13분
        ])
        row = check_against_reference(by_hour).iloc[0]
        assert not row["ok"]

    def test_line_without_reference_passes(self):
        """9호선은 운행현황에 없다 — 참조값이 없다고 실패시키지 않는다."""
        by_hour = pd.DataFrame([
            {"hour": 8, "line": "9", "station": "여의도", "headway_sec": 400},
        ])
        row = check_against_reference(by_hour).iloc[0]
        assert row["reference_rh_min"] is None
        assert row["ok"]

    def test_scope_stations_belong_to_their_line(self):
        assert "서울역" in REFERENCE_SCOPE_STATIONS["1"]
        assert "사당" in REFERENCE_SCOPE_STATIONS["4"]

    def test_thresholds_are_sane(self):
        assert 1.0 < REFERENCE_MAX_RATIO <= 3.0
        assert set(REFERENCE_HEADWAY_MIN) == {str(i) for i in range(1, 9)}


class TestJoinStationCoords:
    @pytest.fixture
    def master(self):
        return pd.DataFrame([
            {"호선": "2호선", "역사명": "강남", "위도": "37.4979", "경도": "127.0276"},
            {"호선": "경인선", "역사명": "부평", "위도": "37.4894", "경도": "126.7246"},
            {"호선": "4호선", "역사명": "불암산", "위도": "37.6699", "경도": "127.0777"},
            {"호선": "수도권 광역급행철도", "역사명": "동탄", "위도": "37.2003", "경도": "127.0957"},
        ])

    def test_direct_match(self, master):
        stations = pd.DataFrame([{"line": "2", "si_id": "A", "station": "강남"}])
        matched, missing = join_station_coords(stations, master)
        assert len(matched) == 1 and missing.empty
        assert matched.iloc[0]["lat"] == pytest.approx(37.4979)

    def test_line_alias_bridges_korail_sections(self, master):
        """역사마스터가 1호선 바깥을 경인선으로 담고 있어도 붙어야 한다."""
        stations = pd.DataFrame([{"line": "1", "si_id": "B", "station": "부평"}])
        matched, missing = join_station_coords(stations, master)
        assert len(matched) == 1 and missing.empty

    def test_station_alias_handles_rename(self, master):
        """당고개는 진접선 연장으로 불암산이 됐다."""
        stations = pd.DataFrame([{"line": "4", "si_id": "C", "station": "당고개"}])
        matched, missing = join_station_coords(stations, master)
        assert len(matched) == 1 and missing.empty

    def test_unknown_station_reported_as_missing(self, master):
        stations = pd.DataFrame([{"line": "2", "si_id": "Z", "station": "없는역"}])
        matched, missing = join_station_coords(stations, master)
        assert matched.empty and len(missing) == 1

    def test_non_numbered_lines_are_ignored(self, master):
        """GTX 등은 역간 소요시간·배차가 없어 그래프에 넣지 않는다."""
        assert "수도권 광역급행철도" not in LINE_ALIAS
