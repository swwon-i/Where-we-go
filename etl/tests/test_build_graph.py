"""build_graph 의 순수 함수 테스트 — DB·원본 없이 돌아간다."""

from __future__ import annotations

import pandas as pd

from etl.build_graph import board_weights_by_hour, split_unpriced_routes


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

    def test_station_without_samples_has_no_key(self):
        """표본이 없는 승강장은 키가 없다. 그러면 BOARD 를 만들지 않는다."""
        hw = pd.DataFrame({"hour": [8], "line": ["2"], "station": ["강남"], "headway_sec": [240]})
        assert ("2", "역삼") not in board_weights_by_hour(hw)
