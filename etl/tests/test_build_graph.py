"""build_graph 의 순수 함수 테스트 — DB·원본 없이 돌아간다."""

from __future__ import annotations

import pandas as pd

import numpy as np

from etl.build_graph import (
    BUS_HOUR_COLS,
    NO_SERVICE,
    aggregate_bus_sections,
    board_weights_by_hour,
    hourly_literal,
    representative,
    split_unpriced_routes,
    subway_service_hours,
    summarize_counts,
)


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
