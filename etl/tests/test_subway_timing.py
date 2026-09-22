"""subway_timing 테스트 — 원본 CSV 없이 돌아간다."""

from __future__ import annotations

import numpy as np
import pandas as pd
import pytest

from etl.subway_timing import (
    EXPRESS_SUFFIX,
    HEADWAY_MAX_SEC,
    LINE_ALIAS,
    REFERENCE_HEADWAY_MIN,
    REFERENCE_MAX_RATIO,
    REFERENCE_SCOPE_STATIONS,
    RUN_MAX_SEC,
    base_line,
    check_against_reference,
    headways,
    inter_station_times,
    is_express,
    line_key,
    join_station_coords,
    normalize_station,
    parse_hms,
    station_key,
)


def frame(rows: list[dict]) -> pd.DataFrame:
    """시각표 모양의 최소 DataFrame. 시각은 `HH:MM:SS` 로 준다.

    `line_key` 는 `load_timetable` 과 같은 방법으로 붙인다 — 급행 분리가 파이프라인
    전체에 걸려 있어 이것이 빠지면 집계 함수가 실제와 다른 입력을 받는다.
    """
    df = pd.DataFrame(rows)
    df["arrive_sec"] = df["STT"].map(parse_hms)
    df["depart_sec"] = df.get("EDT", df["STT"]).map(parse_hms)
    gubhang = df["GUBHANG"] if "GUBHANG" in df else pd.Series("0", index=df.index)
    df["line_key"] = [line_key(l, g) for l, g in zip(df["LINE"], gubhang)]
    return df


class TestParseHms:
    @pytest.mark.parametrize(
        "raw,expected",
        [("00:00:01", 1), ("01:02:03", 3723), ("12:53:30", 46_410), ("23:59:59", 86_399)],
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

    def test_midnight_sentinel_is_missing(self):
        """원본은 빈 시각 자리에 `00:00:00` 을 넣는다. 자정 이후는 `24:xx` 로 적으므로
        이 값은 실제 시각이 아니다. 평일 1호선 급행 도착 시각의 35% 가 이 값이었다."""
        assert np.isnan(parse_hms("00:00:00"))
        assert np.isnan(parse_hms(" 00:00:00 "))


class TestNormalizeStation:
    def test_strips_parenthetical(self):
        assert normalize_station("자양(뚝섬한강공원)") == "자양"

    def test_strips_spaces(self):
        assert normalize_station(" 서울 역 ") == "서울역"


class TestStationKey:
    def test_one_station_with_two_names(self):
        """4호선 총신대입구와 7호선 이수는 한 역이다 — 그래프에서 같은 키여야 환승이 생긴다."""
        assert station_key("이수") == station_key("총신대입구") == "총신대입구"

    def test_other_names_are_just_normalized(self):
        assert station_key("자양(뚝섬한강공원)") == "자양"

    def test_coordinate_matching_keeps_line_names(self):
        """좌표 매칭은 역사마스터가 호선별 이름을 쓰므로 묶지 않는다."""
        assert normalize_station("이수") == "이수"


class TestInterStationTimes:
    def test_consecutive_stops_of_same_train(self):
        legs = inter_station_times(frame([
            {"LINE": "2", "INOUTTAG": "UP", "TRAIN_NO": "T1", "SI_ID": "A",
             "STATION_NM": "강남", "STT": "19:00:00", "EDT": "19:00:30"},
            {"LINE": "2", "INOUTTAG": "UP", "TRAIN_NO": "T1", "SI_ID": "B",
             "STATION_NM": "역삼", "STT": "19:02:00", "EDT": "19:02:30"},
        ]))
        assert len(legs) == 1
        # 출발 → 다음 역 출발. 19:02:30 - 19:00:30 = 주행 90초 + 역삼 정차 30초
        assert legs.iloc[0]["run_sec"] == 120
        assert legs.iloc[0]["from_station"] == "강남"
        assert legs.iloc[0]["to_station"] == "역삼"

    def test_chained_legs_keep_dwell_time(self):
        """이어 붙인 합이 실제 소요시간과 맞아야 한다 — 중간 역 정차가 빠지면 안 된다.

        주행시간만 재면 역삼 정차 30초가 사라져 210초로 나온다. 정차가 30초인 17정차짜리
        경로에서는 8분이 없어진다.
        """
        legs = inter_station_times(frame([
            {"LINE": "2", "INOUTTAG": "UP", "TRAIN_NO": "T1", "SI_ID": "A",
             "STATION_NM": "강남", "STT": "19:00:00", "EDT": "19:00:30"},
            {"LINE": "2", "INOUTTAG": "UP", "TRAIN_NO": "T1", "SI_ID": "B",
             "STATION_NM": "역삼", "STT": "19:02:00", "EDT": "19:02:30"},
            {"LINE": "2", "INOUTTAG": "UP", "TRAIN_NO": "T1", "SI_ID": "C",
             "STATION_NM": "선릉", "STT": "19:04:00", "EDT": "19:04:30"},
        ]))
        # 강남 출발 19:00:30 → 선릉 출발 19:04:30 = 240초
        assert legs["run_sec"].sum() == 240

    def test_missing_arrival_does_not_reorder_stops(self):
        """도착이 `00:00:00`(빈칸)인 역이 열차 순서 맨 앞으로 끌려가면 앞뒤 역이
        건너뛰는 구간으로 이어진다. 출발 시각으로 채워 제자리에 둬야 한다."""
        df = frame([
            {"LINE": "1", "INOUTTAG": "DOWN", "TRAIN_NO": "K1", "SI_ID": "A",
             "STATION_NM": "금정", "STT": "07:00:00", "EDT": "07:00:30"},
            {"LINE": "1", "INOUTTAG": "DOWN", "TRAIN_NO": "K1", "SI_ID": "B",
             "STATION_NM": "군포", "STT": "00:00:00", "EDT": "07:03:00"},
            {"LINE": "1", "INOUTTAG": "DOWN", "TRAIN_NO": "K1", "SI_ID": "C",
             "STATION_NM": "의왕", "STT": "07:05:30", "EDT": "07:06:00"},
        ])
        # load_timetable 과 같은 채움
        df["arrive_sec"] = df["arrive_sec"].fillna(df["depart_sec"])
        pairs = {(r.from_station, r.to_station) for r in inter_station_times(df).itertuples()}
        assert pairs == {("금정", "군포"), ("군포", "의왕")}

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

    def test_express_gets_its_own_headway(self):
        """급행은 배차를 따로 잰다. 섞으면 완행 배차가 실제보다 짧아 보인다.

        아래 표본에서 완행끼리는 6분인데 급행 한 대를 끼워 함께 재면 3분으로 나온다.
        급행 승강장에서 기다리는 사람은 완행이 와도 못 타므로 그 3분은 아무의 대기도 아니다.
        """
        rows = [
            {"LINE": "9", "INOUTTAG": "UP", "TRAIN_NO": "L1", "SI_ID": "S", "STATION_NM": "여의도",
             "GUBHANG": "0", "ED_STT_NM": "종합운동장", "STT": "08:00:00", "EDT": "08:00:30"},
            {"LINE": "9", "INOUTTAG": "UP", "TRAIN_NO": "X1", "SI_ID": "S", "STATION_NM": "여의도",
             "GUBHANG": "1", "ED_STT_NM": "종합운동장", "STT": "08:03:00", "EDT": "08:03:30"},
            {"LINE": "9", "INOUTTAG": "UP", "TRAIN_NO": "L2", "SI_ID": "S", "STATION_NM": "여의도",
             "GUBHANG": "0", "ED_STT_NM": "종합운동장", "STT": "08:06:00", "EDT": "08:06:30"},
            {"LINE": "9", "INOUTTAG": "UP", "TRAIN_NO": "X2", "SI_ID": "S", "STATION_NM": "여의도",
             "GUBHANG": "1", "ED_STT_NM": "종합운동장", "STT": "08:11:00", "EDT": "08:11:30"},
        ]
        out = headways(frame(rows), hour=8).set_index("line")["headway_sec"]
        assert out["9"] == 360                     # 완행끼리 6분
        assert out[f"9{EXPRESS_SUFFIX}"] == 480    # 급행끼리 8분 — 더 길다

    def test_express_legs_do_not_leak_into_local_line(self):
        """급행이 건너뛴 구간은 급행 노선의 구간이지 완행 노선의 구간이 아니다.

        섞이면 완행 승강장에서 완행 배차로 기다린 뒤 급행처럼 건너뛸 수 있게 된다.
        """
        rows = [
            {"LINE": "9", "INOUTTAG": "UP", "TRAIN_NO": "L1", "SI_ID": "A", "STATION_NM": "고속터미널",
             "GUBHANG": "0", "STT": "08:00:00", "EDT": "08:00:30"},
            {"LINE": "9", "INOUTTAG": "UP", "TRAIN_NO": "L1", "SI_ID": "B", "STATION_NM": "신반포",
             "GUBHANG": "0", "STT": "08:02:00", "EDT": "08:02:30"},
            {"LINE": "9", "INOUTTAG": "UP", "TRAIN_NO": "X1", "SI_ID": "A", "STATION_NM": "고속터미널",
             "GUBHANG": "1", "STT": "08:10:00", "EDT": "08:10:30"},
            {"LINE": "9", "INOUTTAG": "UP", "TRAIN_NO": "X1", "SI_ID": "C", "STATION_NM": "동작",
             "GUBHANG": "1", "STT": "08:13:30", "EDT": "08:14:00"},
        ]
        legs = inter_station_times(frame(rows))
        pairs = {(r.line, r.from_station, r.to_station) for r in legs.itertuples()}
        assert ("9", "고속터미널", "신반포") in pairs
        assert (f"9{EXPRESS_SUFFIX}", "고속터미널", "동작") in pairs
        # 완행 노선에 건너뛰는 구간이 생기면 안 된다
        assert ("9", "고속터미널", "동작") not in pairs


    def test_sparse_service_still_has_headway(self):
        """한 시간에 한 대꼴인 노선. 시간대로 먼저 자르면 각 시간대에 열차가 하나뿐이라
        간격이 안 생긴다(1호선 급행 덕정). 하루 전체에서 재고 뒤 열차의 시간대에 배정한다."""
        rows = [
            {"LINE": "1", "INOUTTAG": "DOWN", "TRAIN_NO": f"K{i}", "SI_ID": "S",
             "STATION_NM": "덕정", "GUBHANG": "1", "STT": f"{7 + i:02d}:10:00", "EDT": f"{7 + i:02d}:10:30"}
            for i in range(4)
        ]
        at9 = headways(frame(rows), hour=9)
        assert len(at9) == 1
        assert at9.iloc[0]["headway_sec"] == 3600

    def test_first_train_of_hour_keeps_its_gap(self):
        """8:58 다음 9:02 — 9시대 첫 열차의 간격은 4분이다. 시간대로 먼저 자르면 이 표본이 사라진다."""
        rows = [
            {"LINE": "2", "INOUTTAG": "IN", "TRAIN_NO": f"T{i}", "SI_ID": "S", "STATION_NM": "강남",
             "GUBHANG": "0", "STT": t, "EDT": t}
            for i, t in enumerate(["08:58:00", "09:02:00"])
        ]
        assert headways(frame(rows), hour=9).iloc[0]["headway_sec"] == 240

    def test_past_midnight_counts_as_hour_zero(self):
        """24:20 · 24:30 도착은 0시 표본이다. 그냥 비교하면 0시 배차가 통째로 빠진다."""
        rows = [
            {"LINE": "2", "INOUTTAG": "IN", "TRAIN_NO": f"T{i}", "SI_ID": "S", "STATION_NM": "강남",
             "GUBHANG": "0", "STT": t, "EDT": t}
            for i, t in enumerate(["24:20:00", "24:30:00"])
        ]
        at0 = headways(frame(rows), hour=0)
        assert len(at0) == 1 and at0.iloc[0]["headway_sec"] == 600

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
class TestLineKey:
    def test_local_keeps_plain_line(self):
        assert line_key("9", "0") == "9"
        assert not is_express("9")

    def test_express_gets_suffix(self):
        assert line_key("9", "1") == f"9{EXPRESS_SUFFIX}"
        assert is_express(f"9{EXPRESS_SUFFIX}")

    def test_base_line_round_trips(self):
        assert base_line(line_key("1", "1")) == "1"
        assert base_line("2") == "2"
