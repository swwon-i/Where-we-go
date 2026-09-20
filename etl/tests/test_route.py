"""경로탐색 로직 테스트 — DB 없이 합성 그래프로 돈다.

빌드된 실제 그래프를 쓰는 검증은 `test_route_cases.py` 에 있다.
"""

from __future__ import annotations

import pytest

from etl.route import Graph, Route, Leg, build_route, dijkstra, route_between


def make_graph(edges, meta=None, route_names=None):
    """`edges` 는 `(from, to, weight, kind, line)` 또는 시간대 배열을 포함한 6튜플."""
    from collections import defaultdict

    adj = defaultdict(list)
    for e in edges:
        if len(e) == 5:
            f, t, w, kind, line = e
            by_hour = None
        else:
            f, t, w, by_hour, kind, line = e
        adj[f].append((t, w, by_hour, kind, line))
    return Graph(adj, meta or {}, {}, build_id=0, route_names=route_names)


class TestDijkstra:
    def test_finds_shortest_not_fewest_hops(self):
        """멀리 돌더라도 시간이 짧으면 그쪽이다."""
        g = make_graph([
            (1, 2, 100, "WALK", None),
            (1, 3, 10, "WALK", None),
            (3, 2, 10, "WALK", None),
        ])
        dist, _, _ = dijkstra(g, 1, {2})
        assert dist[2] == 20

    def test_unreachable_target_absent(self):
        g = make_graph([(1, 2, 10, "WALK", None)])
        dist, _, _ = dijkstra(g, 1, {99})
        assert 99 not in dist

    def test_stops_once_all_targets_settled(self):
        """조기 종료 — 목표를 다 찾으면 나머지 그래프를 훑지 않는다.

        후보 반경 축소와 함께 탐색 비용을 줄이는 수단이다.
        """
        chain = [(i, i + 1, 1, "WALK", None) for i in range(1, 100)]
        g = make_graph(chain)
        _, _, seen_near = dijkstra(g, 1, {3})
        _, _, seen_far = dijkstra(g, 1, {99})
        assert seen_near < seen_far

    def test_single_traversal_covers_many_targets(self):
        """one-to-many — 목표가 늘어도 탐색은 한 번이다.

        가장 먼 목표까지만 퍼뜨리면 가까운 것들은 가는 길에 이미 확정된다.
        one-to-one 을 반복하면 앞부분을 매번 다시 계산하게 된다.
        """
        chain = [(i, i + 1, 1, "WALK", None) for i in range(1, 50)]
        g = make_graph(chain)
        _, _, only_far = dijkstra(g, 1, {49})
        _, _, many = dijkstra(g, 1, {5, 15, 25, 35, 49})
        assert many == only_far

    def test_hour_selects_from_array(self):
        """시간대 배열이 있으면 departureHour 로 고른다."""
        by_hour = [10] * 24
        by_hour[19] = 500
        g = make_graph([(1, 2, 10, by_hour, "RIDE", "2")])
        assert dijkstra(g, 1, {2}, hour=8)[0][2] == 10
        assert dijkstra(g, 1, {2}, hour=19)[0][2] == 500

    def test_hour_none_uses_base_weight(self):
        by_hour = [999] * 24
        g = make_graph([(1, 2, 42, by_hour, "RIDE", "2")])
        assert dijkstra(g, 1, {2}, hour=None)[0][2] == 42

    def test_hour_changes_chosen_path(self):
        """시간대에 따라 다른 경로가 선택되어야 한다 — 이것이 시간대 비교 기능의 근거다."""
        fast_then_slow = [50] * 24
        fast_then_slow[19] = 900
        g = make_graph([
            (1, 2, 50, fast_then_slow, "RIDE", "A"),
            (1, 3, 100, None, "RIDE", "B"),
            (3, 2, 100, None, "RIDE", "B"),
        ])
        assert dijkstra(g, 1, {2}, hour=8)[0][2] == 50    # A 직통
        assert dijkstra(g, 1, {2}, hour=19)[0][2] == 200  # B 우회


class TestBuildRoute:
    META = {
        10: ("STOP", "SUBWAY", None, "강남"),
        11: ("PLATFORM", "SUBWAY", "2", "강남"),
        12: ("PLATFORM", "SUBWAY", "2", "역삼"),
        13: ("PLATFORM", "SUBWAY", "2", "선릉"),
        14: ("STOP", "SUBWAY", None, "선릉"),
    }

    def test_consecutive_rides_merge_into_one_leg(self):
        """같은 노선을 연달아 타면 한 구간으로 묶여야 한다 — 정차마다 끊으면 읽을 수 없다."""
        g = make_graph([
            (10, 11, 90, "BOARD", "2"),
            (11, 12, 100, "RIDE", "2"),
            (12, 13, 110, "RIDE", "2"),
            (13, 14, 44, "ALIGHT", "2"),
        ], self.META)
        dist, prev, _ = dijkstra(g, 10, {14})
        route = build_route(g, prev, dist, 14)
        rides = [l for l in route.legs if l.kind == "SUBWAY"]
        assert len(rides) == 1
        assert rides[0].stops == 2
        assert route.total_sec == 344

    def test_transfer_count_excludes_first_boarding(self):
        """처음 타는 것은 환승이 아니다."""
        meta = dict(self.META)
        meta[20] = ("PLATFORM", "SUBWAY", "3", "교대")
        meta[21] = ("PLATFORM", "SUBWAY", "3", "고속터미널")
        g = make_graph([
            (10, 11, 90, "BOARD", "2"),
            (11, 12, 100, "RIDE", "2"),
            (12, 20, 150, "TRANSFER", "3"),
            (20, 21, 120, "RIDE", "3"),
        ], meta)
        dist, prev, _ = dijkstra(g, 10, {21})
        route = build_route(g, prev, dist, 21)
        assert route.transfers == 1

    def test_walk_only_route_has_no_transfers(self):
        g = make_graph([(1, 2, 60, "WALK", None), (2, 3, 60, "WALK", None)])
        dist, prev, _ = dijkstra(g, 1, {3})
        route = build_route(g, prev, dist, 3)
        assert route.transfers == 0
        assert [l.kind for l in route.legs] == ["WALK"]
        assert route.walk_distance_m == pytest.approx(144.0)

    def test_unreachable_returns_none(self):
        g = make_graph([(1, 2, 10, "WALK", None)])
        dist, prev, _ = dijkstra(g, 1, {99})
        assert build_route(g, prev, dist, 99) is None

    def test_subway_and_bus_are_distinct_legs(self):
        meta = {
            30: ("STOP", "BUS", None, "강남역"),
            31: ("PLATFORM", "BUS", "146", "강남역"),
            32: ("PLATFORM", "BUS", "146", "역삼역"),
            33: ("STOP", "BUS", None, "역삼역"),
        }
        meta.update(self.META)
        g = make_graph([
            (10, 11, 90, "BOARD", "2"),
            (11, 12, 100, "RIDE", "2"),
            (12, 14, 44, "ALIGHT", "2"),
            (14, 30, 120, "WALK", None),
            (30, 31, 200, "BOARD", "146"),
            (31, 32, 180, "RIDE", "146"),
            (32, 33, 1, "ALIGHT", "146"),
        ], meta)
        dist, prev, _ = dijkstra(g, 10, {33})
        route = build_route(g, prev, dist, 33)
        kinds = [l.kind for l in route.legs]
        assert "SUBWAY" in kinds and "BUS" in kinds and "WALK" in kinds
        assert route.transfers == 1


class TestRouteNames:
    """버스 `line` 은 '100100017' 같은 식별자라 그대로 보여주면 읽을 수 없다.
    이름은 얹되 **식별자는 그대로 둔다** — 노선을 가르는 키가 `line` 이기 때문이다."""

    META = {
        30: ("STOP", "BUS", None, "북한산"),
        31: ("PLATFORM", "BUS", "100100017", "북한산"),
        32: ("PLATFORM", "BUS", "100100017", "수유역"),
        33: ("STOP", "BUS", None, "수유역"),
    }
    EDGES = [
        (30, 31, 200, "BOARD", "100100017"),
        (31, 32, 600, "RIDE", "100100017"),
        (32, 33, 1, "ALIGHT", "100100017"),
    ]

    def route(self, names):
        g = make_graph(self.EDGES, self.META, names)
        dist, prev, _ = dijkstra(g, 30, {33})
        return build_route(g, prev, dist, 33)

    def test_display_name_replaces_id_in_summary(self):
        route = self.route({("BUS", "100100017"): "120"})
        assert "120" in route.summary()
        assert "100100017" not in route.summary()

    def test_identity_is_preserved(self):
        """표시 이름이 붙어도 `line` 은 식별자 그대로여야 한다."""
        leg = [l for l in self.route({("BUS", "100100017"): "120"}).legs
               if l.kind == "BUS"][0]
        assert leg.line == "100100017"
        assert leg.line_name == "120"

    def test_falls_back_to_id_when_name_missing(self):
        """이름표가 없는 노선도 경로는 나와야 한다 — 표시만 식별자로 떨어진다."""
        leg = [l for l in self.route({}).legs if l.kind == "BUS"][0]
        assert leg.label == "100100017"


class TestRouteSummary:
    def test_summary_mentions_lines_in_order(self):
        route = Route(1800, [
            Leg("WALK", None, 120, 0, 144.0, "강남"),
            Leg("SUBWAY", "2", 600, 5, 0.0, "교대"),
            Leg("SUBWAY", "3", 900, 8, 0.0, "종로3가"),
        ])
        s = route.summary()
        assert "2 → 3" in s and "환승 1" in s

    def test_walk_only_summary(self):
        route = Route(300, [Leg("WALK", None, 300, 0, 360.0, None)])
        assert "도보" in route.summary()
        assert route.transfers == 0
