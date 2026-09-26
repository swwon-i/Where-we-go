"""build_graph 의 순수 함수 테스트 — DB·원본 없이 돌아간다."""

from __future__ import annotations

import pandas as pd

import numpy as np

from etl.build_graph import (
    BUS_HOUR_COLS,
    BUS_STOP_COORD_FIX,
    NO_SERVICE,
    TRANSFER_PLACEHOLDER_SEC,
    aggregate_bus_sections,
    apply_stop_coord_fix,
    board_weights_by_hour,
    calibrate_transfers,
    load_measured_transfers,
    platform_traverse_seconds,
    hourly_literal,
    representative,
    split_unpriced_routes,
    subway_service_hours,
    summarize_counts,
)


class TestApplyStopCoordFix:
    """원본 좌표가 틀린 정류장만 보정한다. 나머지는 손대지 않는다."""

    def _master(self, rows):
        df = pd.DataFrame(rows, columns=["정류장_ID", "lng", "lat"])
        return df

    def test_replaces_listed_stop(self):
        master = self._master([["113900266", 127.075, 37.6136], ["113900164", 126.9174, 37.5669]])
        out, fixed, stale = apply_stop_coord_fix(master, {"113900266": (126.9176417, 37.5672426)})
        row = out[out["정류장_ID"] == "113900266"].iloc[0]
        assert (round(row["lng"], 5), round(row["lat"], 5)) == (126.91764, 37.56724)
        assert (fixed, stale) == (1, [])

    def test_leaves_others_alone(self):
        master = self._master([["113900266", 127.075, 37.6136], ["113900164", 126.9174, 37.5669]])
        out, _, _ = apply_stop_coord_fix(master, {"113900266": (126.9176417, 37.5672426)})
        row = out[out["정류장_ID"] == "113900164"].iloc[0]
        assert (row["lng"], row["lat"]) == (126.9174, 37.5669)

    def test_reports_ids_missing_from_master(self):
        """원본이 고쳐졌거나 정류장이 사라지면 목록이 낡는다 — 조용히 넘어가지 않는다."""
        master = self._master([["113900164", 126.9174, 37.5669]])
        out, fixed, stale = apply_stop_coord_fix(master, {"999999999": (126.0, 37.0)})
        assert (fixed, stale) == (0, ["999999999"])
        assert len(out) == 1

    def test_real_list_is_seoul(self):
        """실제 보정 목록의 좌표가 서울 안인지 — 자릿수를 잘못 적으면 여기서 걸린다."""
        for stop_id, (lng, lat) in BUS_STOP_COORD_FIX.items():
            assert 126.7 < lng < 127.2, stop_id
            assert 37.4 < lat < 37.7, stop_id


class TestSplitUnpricedRoutes:
    """배차를 모르는 노선은 대기를 지어내지 않고 그래프에서 뺀다."""

    def test_drops_routes_without_headway(self):
        sections = pd.DataFrame({
            "노선_ID": ["R1", "R1", "DAWN", "R2"],
            "출발_정류장_ID": ["a", "b", "c", "d"],
        })
        kept, dropped = split_unpriced_routes(sections, {"R1": 600, "R2": 900})
        assert dropped == ["DAWN"]
        assert set(kept["노선_ID"]) == {"R1", "R2"}
        assert len(kept) == 3

    def test_nothing_dropped_when_all_priced(self):
        sections = pd.DataFrame({"노선_ID": ["R1"], "출발_정류장_ID": ["a"]})
        kept, dropped = split_unpriced_routes(sections, {"R1": 600})
        assert dropped == [] and len(kept) == 1


class TestBoardWeightsByHour:
    def test_fills_missing_hours_from_neighbours(self):
        """표본이 한 시간대만 있어도 24칸이 모두 채워진다 — 폴백 상수가 필요 없다."""
        hw = pd.DataFrame({
            "hour": [8], "line": ["2"], "station": ["강남"], "headway_sec": [240],
        })
        w = board_weights_by_hour(hw)[("2", "강남")]
        assert len(w) == 24
        assert set(w) == {120}

    def test_hours_without_trains_are_no_service(self):
        """열차가 서지 않는 시간대는 앞뒤 값으로 채우지 않는다. 새벽 4시에 지하철이 다니면 안 된다."""
        hw = pd.DataFrame({"hour": [8], "line": ["2"], "station": ["강남"], "headway_sec": [240]})
        w = board_weights_by_hour(hw, {("2", "강남"): {5, 6, 7, 8}})[("2", "강남")]
        assert w[4] == NO_SERVICE
        assert w[5] == 120          # 열차는 서는데 표본이 없는 첫차 시간대는 이웃 값으로 메운다
        assert w[8] == 120
        assert w[9] == NO_SERVICE

    def test_station_without_samples_has_no_key(self):
        """표본이 없는 승강장은 키가 없다. 그러면 BOARD 를 만들지 않는다."""
        hw = pd.DataFrame({"hour": [8], "line": ["2"], "station": ["강남"], "headway_sec": [240]})
        assert ("2", "역삼") not in board_weights_by_hour(hw)


class TestSubwayServiceHours:
    def test_past_midnight_wraps_to_early_hours(self):
        """25:14 도착은 1시 운행이다."""
        tt = pd.DataFrame({
            "line_key": ["2", "2"], "STATION_NM": ["강남", "강남"],
            "arrive_sec": [8 * 3600 + 60, 25 * 3600 + 14 * 60],
        })
        assert subway_service_hours(tt)[("2", "강남")] == {8, 1}


def _section_rows(values_by_day: list[dict[int, int]]):
    """구간 하나를 날짜별로. `{시간대: 초}` 에 없는 칸은 0(운행 없음)."""
    rows = []
    for i, values in enumerate(values_by_day):
        row = {"기준_날짜": f"2026090{i}", "노선_ID": "N26",
               "출발_정류장_ID": "a", "도착_정류장_ID": "b"}
        row.update({c: str(values.get(h, 0)) for h, c in enumerate(BUS_HOUR_COLS)})
        rows.append(row)
    return pd.DataFrame(rows)


class TestAggregateBusSections:
    def test_median_over_days_ignores_zero(self):
        """운행한 날들의 중앙값. 0 을 섞으면 짧아 보인다."""
        sec = _section_rows([{0: 95}, {0: 124}, {0: 128}, {0: 134}, {0: 135}])
        out = aggregate_bus_sections(sec, days=5)
        assert len(out) == 1
        assert out.iloc[0][BUS_HOUR_COLS[0]] == 128

    def test_hours_nobody_ran_are_no_service(self):
        """N26 은 5~23시 원본이 0 이다. 예전에는 중앙값으로 채워 낮에도 다녔다."""
        sec = _section_rows([{0: 100, 1: 150}] * 5)
        row = aggregate_bus_sections(sec, days=5).iloc[0]
        assert row[BUS_HOUR_COLS[1]] == 150
        assert all(row[c] == NO_SERVICE for c in BUS_HOUR_COLS[2:])

    def test_minority_days_are_no_service(self):
        """5일 중 2일만 찍힌 시간대는 첫차·막차 경계에 우연히 걸친 것이다."""
        sec = _section_rows([{5: 90}, {5: 95}, {}, {}, {}])
        assert aggregate_bus_sections(sec, days=5).iloc[0][BUS_HOUR_COLS[5]] == NO_SERVICE

    def test_majority_days_run(self):
        sec = _section_rows([{5: 90}, {5: 95}, {5: 100}, {}, {}])
        assert aggregate_bus_sections(sec, days=5).iloc[0][BUS_HOUR_COLS[5]] == 95


class TestHourlyLiteral:
    def test_offset_skips_no_service(self):
        """승강장 도보를 더할 때 운행 없음(-1)에 더하면 운행하는 값이 되어 버린다."""
        assert hourly_literal([10, NO_SERVICE, 20], offset=40) == "{50,-1,60}"

    def test_representative_ignores_no_service(self):
        assert representative([NO_SERVICE] * 19 + [95, 175, 125, 112, 112]) == 112
        assert representative([NO_SERVICE] * 24) == NO_SERVICE


class TestSummarizeCounts:
    """graph_build 합계 칸은 수단을 가리지 않고 더한다. 예전에는 지하철만 세어
    정류장 402 · 플랫폼 562 로 기록돼 있었다(버스 11,042 · 36,606 이 빠짐)."""

    ROWS = [
        ("NODE", "STOP", "SUBWAY", 402), ("NODE", "STOP", "BUS", 11_042),
        ("NODE", "PLATFORM", "SUBWAY", 562), ("NODE", "PLATFORM", "BUS", 36_606),
        ("NODE", "WALK", "WALK", 165_050),
        ("EDGE", "ALIGHT", "SUBWAY", 562), ("EDGE", "ALIGHT", "BUS", 36_606),
        ("EDGE", "ACCESS", "WALK", 11_308), ("EDGE", "ACCESS", "SUBWAY", 280),
    ]

    def test_adds_bus_to_subway(self):
        t = summarize_counts(self.ROWS)
        assert t["nodes_stop"] == 11_444
        assert t["nodes_platform"] == 37_168

    def test_alight_is_counted(self):
        """하차 엣지는 칸조차 없었다."""
        assert summarize_counts(self.ROWS)["edges_alight"] == 37_168

    def test_access_counts_both_directions(self):
        assert summarize_counts(self.ROWS)["edges_access"] == 11_588

    def test_missing_kind_is_zero(self):
        assert summarize_counts(self.ROWS)["edges_transfer"] == 0


MEASURED_COLS = ["환승시작역", "환승시작 호선", "환승종료역", "환승종료 호선", "소요시간"]


def _measured_csv(tmp_path, rows):
    p = tmp_path / "measured.csv"
    pd.DataFrame(rows, columns=MEASURED_COLS).to_csv(p, index=False, encoding="cp949")
    return p


class TestLoadMeasuredTransfers:
    def test_median_over_doors(self, tmp_path):
        """같은 쌍이 하차·승차 칸마다 여러 행이다. 쌍마다 중앙값 하나로 모은다."""
        p = _measured_csv(tmp_path, [
            ["서울역", "1", "서울역", "4", "03:30"],
            ["서울역", "1", "서울역", "4", "03:34"],
            ["서울역", "1", "서울역", "4", "03:40"],
            ["서울역", "4", "서울역", "1", "02:00"],
        ])
        t = load_measured_transfers(p).set_index(["station", "line_a", "line_b"])["sec"]
        assert t[("서울역", "1", "4")] == 214
        assert t[("서울역", "4", "1")] == 120     # 방향은 원본 그대로 — 뒤집어 만들지 않는다

    def test_placeholder_is_dropped(self, tmp_path):
        """10:00 은 잰 값이 아니다. 9호선 1단계가 전부 이 값이다."""
        assert TRANSFER_PLACEHOLDER_SEC == 600
        p = _measured_csv(tmp_path, [
            ["당산", "2", "당산", "9", "10:00"],
            ["노량진", "1", "노량진", "9", "07:00"],
        ])
        t = load_measured_transfers(p)
        assert list(t["station"]) == ["노량진"]

    def test_non_numeric_lines_are_dropped(self, tmp_path):
        p = _measured_csv(tmp_path, [
            ["서울역", "1", "서울역", "공항철도", "05:00"],
            ["시청", "1", "시청", "2", "03:32"],
        ])
        assert list(load_measured_transfers(p)["station"]) == ["시청"]

    def test_one_station_with_two_names_is_kept(self, tmp_path):
        """총신대입구 4 ↔ 이수 7 은 이름만 다른 같은 역이다. 그래프의 역 키로 묶어 남긴다."""
        p = _measured_csv(tmp_path, [
            ["총신대입구", "4", "이수", "7", "02:51"],
            ["이수", "7", "총신대입구", "4", "02:51"],
            ["서울역", "1", "시청", "2", "03:00"],     # 다른 역끼리는 환승이 아니다
        ])
        t = load_measured_transfers(p)
        assert set(zip(t.station, t.line_a, t.line_b)) == {("총신대입구", "4", "7"), ("총신대입구", "7", "4")}


def _pairs(rows):
    return pd.DataFrame(rows, columns=["station", "line_a", "line_b", "sec"])


class TestCalibrateTransfers:
    measured = _pairs([["시청", "1", "2", 212], ["건대입구", "2", "7", 152], ["충무로", "3", "4", 101]])
    distance = _pairs([
        ["시청", "1", "2", 120], ["건대입구", "2", "7", 64], ["충무로", "3", "4", 14],
        ["당산", "2", "9", 88],
    ])

    def test_measured_wins(self):
        t, _, _ = calibrate_transfers(self.measured, self.distance)
        t = t.set_index(["station", "line_a", "line_b"])
        assert t.loc[("시청", "1", "2"), "sec"] == 212
        assert t.loc[("시청", "1", "2"), "source"] == "MEASURED"

    def test_offset_is_median_of_overlap(self):
        """차이 92 · 88 · 87 의 중앙값 88. 상수를 박지 않고 겹치는 쌍에서 유도한다."""
        _, offset, overlap = calibrate_transfers(self.measured, self.distance)
        assert (offset, overlap) == (88, 3)

    def test_missing_pair_is_calibrated(self):
        """9호선처럼 측정값이 없는 쌍은 거리÷1.2 에 보정초를 더한다 — 싼 값 그대로 두면 경로가 쏠린다."""
        t, _, _ = calibrate_transfers(self.measured, self.distance)
        t = t.set_index(["station", "line_a", "line_b"])
        assert t.loc[("당산", "2", "9"), "sec"] == 88 + 88
        assert t.loc[("당산", "2", "9"), "source"] == "CALIBRATED"

    def test_measured_only_pair_is_kept(self):
        """거리 파일에 없는 쌍(노량진 1↔9)도 측정값이 있으면 쓴다."""
        m = pd.concat([self.measured, _pairs([["노량진", "1", "9", 420]])])
        t, _, _ = calibrate_transfers(m, self.distance)
        assert ("노량진", "1", "9") in set(zip(t.station, t.line_a, t.line_b))
        assert len(t) == 5

    def test_no_overlap_is_an_error(self):
        import pytest
        with pytest.raises(ValueError):
            calibrate_transfers(_pairs([["a", "1", "2", 100]]), _pairs([["b", "1", "2", 50]]))


class TestPlatformTraverseSeconds:
    """대합실로 나갔다 다시 타는 길(ALIGHT + BOARD)이 환승 통로보다 싸면 환승값이 무시된다."""

    # 종로3가를 줄인 것 — 1↔3, 3↔5 는 짧고 1↔5 만 길다
    xfer = _pairs([
        ["종로3가", "1", "3", 161], ["종로3가", "3", "1", 161],
        ["종로3가", "3", "5", 170], ["종로3가", "5", "3", 170],
        ["종로3가", "1", "5", 326], ["종로3가", "5", "1", 326],
    ])

    def test_every_pair_prefers_the_transfer(self):
        t = platform_traverse_seconds(self.xfer, {"종로3가": 88}, 88)
        for r in self.xfer.itertuples():
            assert t[(r.station, r.line_a)] + t[(r.station, r.line_b)] > r.sec

    def test_only_short_platforms_are_raised(self):
        """1↔3 은 이미 맞으므로 3호선은 역 값 그대로다. 역 전체를 올리지 않는다."""
        t = platform_traverse_seconds(self.xfer, {"종로3가": 88}, 88)
        assert t[("종로3가", "3")] == 88
        assert t[("종로3가", "1")] > 88 and t[("종로3가", "5")] > 88

    def test_nothing_changes_when_already_fine(self):
        t = platform_traverse_seconds(_pairs([["시청", "1", "2", 150], ["시청", "2", "1", 150]]),
                                      {"시청": 88}, 88)
        assert t == {("시청", "1"): 88, ("시청", "2"): 88}

    def test_tie_is_broken_toward_the_transfer(self):
        """같으면 어느 길을 탈지 정해지지 않는다 — 1초라도 대합실 쪽이 비싸야 한다."""
        t = platform_traverse_seconds(_pairs([["군자", "5", "7", 176]]), {"군자": 88}, 88)
        assert t[("군자", "5")] + t[("군자", "7")] == 177
