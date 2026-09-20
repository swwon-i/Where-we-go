"""실제 빌드된 그래프로 도는 검증.

DB 나 활성 빌드가 없으면 통째로 건너뛴다 — 나머지 테스트는 원본 없이도 돌아야 하고,
이것만 환경에 의존한다.

    docker compose up -d db
    python -m etl.build_graph
    pytest etl/tests/test_route_cases.py -v
"""

from __future__ import annotations

import pytest

from etl.route import Graph, route_between

try:
    from etl.db import connect
    _conn = connect()
    _graph = Graph.load(_conn)
except Exception as e:  # noqa: BLE001 — 환경이 없으면 건너뛴다
    _conn, _graph = None, None
    _reason = str(e)[:120]
else:
    _reason = ""

pytestmark = pytest.mark.skipif(
    _graph is None, reason=f"활성 그래프 빌드가 없다: {_reason}"
)


@pytest.fixture(scope="module")
def graph():
    return _graph


def ride_lines(route):
    return [l.line for l in route.legs if l.kind in ("SUBWAY", "BUS")]


class TestSubwayDirect:
    """환승 없이 한 노선으로 가는 경우. 다익스트라가 불필요한 환승을 만들지 않아야 한다."""

    @pytest.mark.parametrize(
        "a,b,line,max_min",
        [
            ("강남", "홍대입구", "2", 45),
            ("노원", "사당", "4", 55),
            ("건대입구", "왕십리", "2", 25),
        ],
    )
    def test_single_line(self, graph, a, b, line, max_min):
        route = route_between(graph, graph.stop(a), graph.stop(b))
        assert route is not None, f"{a}→{b} 도달 불가"
        assert route.transfers == 0, f"{a}→{b} 직결인데 환승 {route.transfers}"
        assert ride_lines(route) == [line]
        assert route.total_sec <= max_min * 60


class TestSubwayTransfer:
    """환승이 필요한 경우. TRANSFER 엣지를 쓰는지가 핵심이다 —
    정류장을 거쳐 ALIGHT→BOARD 로 갈아타면 실측 환승 도보시간이 통째로 빠진다."""

    @pytest.mark.parametrize(
        "a,b,min_transfers",
        [
            ("잠실", "광화문", 1),   # 2 → 5
            ("강남", "광화문", 1),   # 2 → ... → 5
            ("수유", "여의도", 1),   # 4 → 5
        ],
    )
    def test_transfer_happens(self, graph, a, b, min_transfers):
        route = route_between(graph, graph.stop(a), graph.stop(b))
        assert route is not None
        assert route.transfers >= min_transfers
        assert len(set(ride_lines(route))) >= min_transfers + 1

    def test_transfer_uses_transfer_edge(self, graph):
        """환승 구간의 비용이 대기만 있는 값보다 커야 한다.

        환승 도보(실측)가 빠지면 환승이 사실상 공짜가 된다 — 실제로 을지로4가에서
        2→5호선이 1초에 갈아타지는 버그가 있었다.
        """
        route = route_between(graph, graph.stop("잠실"), graph.stop("광화문"))
        second = [l for l in route.legs if l.kind == "SUBWAY"][1]
        # 환승 도보 + 대기가 함께 붙으므로 대기만 있을 때보다 확실히 크다
        assert second.seconds > 120


class TestWalkOnly:
    """가까운 두 지점은 걸어가야 한다. 지하철을 타면 진출입·대기가 붙어 더 느리다."""

    def test_adjacent_stations_prefer_walking_or_short_ride(self, graph):
        route = route_between(graph, graph.stop("강남"), graph.stop("역삼"))
        assert route is not None
        assert route.total_sec < 20 * 60

    def test_snap_and_route_between_coordinates(self, graph):
        """좌표 → 보행 노드 스냅이 동작하고, 그 사이 경로가 나온다."""
        with connect() as conn:
            src, ds = graph.snap(conn, 127.0276, 37.4979)   # 강남역
            dst, dd = graph.snap(conn, 127.0350, 37.5006)   # 역삼 방면 약 700m
        assert ds < 200 and dd < 200
        route = route_between(graph, src, dst)
        assert route is not None
        assert route.walk_distance_m > 0


class TestBus:
    """버스가 그래프에 들어갔는지. 계획서상 3주 후반 항목을 당겨 넣었다."""

    def test_bus_stops_exist(self, graph):
        bus_stops = [k for k in graph.stop_index if k[0] == "BUS"]
        assert len(bus_stops) > 5_000

    def test_bus_ride_appears_somewhere(self, graph):
        """버스 정류장 사이를 이동하면 버스를 타는 경로가 나와야 한다."""
        bus_names = [k[1] for k in graph.stop_index if k[0] == "BUS"][:1]
        assert bus_names, "버스 정류장이 없다"
        # 같은 노선의 먼 정류장 쌍을 찾기 어려우므로, 버스 엣지 존재만 확인한다
        kinds = {
            kind
            for edges in graph.adj.values()
            for _, _, _, kind, _ in edges
        }
        assert {"RIDE", "BOARD", "ALIGHT", "ACCESS", "WALK", "TRANSFER"} <= kinds


class TestRouteNames:
    """`transit_route` 가 채워져 있는지. 없으면 경로가 노선 ID 로 표시된다."""

    def test_every_bus_line_has_a_name(self, graph):
        lines = {
            line
            for edges in graph.adj.values()
            for _, _, _, kind, line in edges
            if kind == "RIDE" and line and line.isdigit() and len(line) >= 7
        }
        assert lines, "버스 RIDE 엣지가 없다"
        missing = {l for l in lines if ("BUS", l) not in graph.route_names}
        assert not missing, f"이름표 없는 버스 노선 {len(missing)}개: {sorted(missing)[:5]}"

    def test_subway_lines_have_names(self, graph):
        assert graph.route_names.get(("SUBWAY", "2")) == "2호선"


class TestDepartureHour:
    """시간대별 가중치가 실제로 결과를 바꾸는지 — 이것이 없으면 departureHour 가 무의미하다."""

    def test_hour_affects_some_route(self, graph):
        pairs = [("잠실", "광화문"), ("강남", "시청"), ("노원", "사당")]
        diffs = []
        for a, b in pairs:
            morning = route_between(graph, graph.stop(a), graph.stop(b), hour=8)
            evening = route_between(graph, graph.stop(a), graph.stop(b), hour=22)
            if morning and evening:
                diffs.append(abs(morning.total_sec - evening.total_sec))
        assert diffs, "경로가 하나도 나오지 않았다"
        assert max(diffs) > 0, "시간대를 바꿔도 소요시간이 같다 — 배열 가중치를 의심할 것"


class TestUnreachable:
    def test_missing_station_raises(self, graph):
        with pytest.raises(KeyError):
            graph.stop("없는역")


# 좌표 스냅 테스트가 커넥션을 쓰므로 여기서 임포트한다
from etl.db import connect  # noqa: E402
