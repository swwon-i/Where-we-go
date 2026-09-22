"""경로탐색 참조 구현 — 빌드된 그래프가 쓸 만한지 확인하기 위한 것.

서비스의 탐색은 Spring Boot 쪽에서 한다(계획서 2주 후반). 이쪽은 빌드 결과를 눈으로
확인하고 테스트로 고정하기 위한 **검증용**이며, 두 구현이 같은 답을 내는지 대조하는
기준으로도 쓸 수 있다.

    python -m etl.route 강남 시청
    python -m etl.route 강남 시청 --hour 19
    python -m etl.route --from-coord 127.0276,37.4979 --to-coord 126.9779,37.5663
"""

from __future__ import annotations

import argparse
import heapq
import sys
from collections import defaultdict
from dataclasses import dataclass, replace

from etl.db import connect

#: 승차·환승 엣지. 노선이 바뀌는 지점이다.
BOARDING_KINDS = {"BOARD", "TRANSFER"}

#: 탈것 줄. 승차·환승 줄과 달리 주행만 담는다.
RIDE_KINDS = {"SUBWAY", "BUS"}


@dataclass(frozen=True)
class Leg:
    """경로를 사람이 읽는 단위로 묶은 것. 같은 노선을 연속으로 타면 한 구간이다."""

    kind: str          # WALK / SUBWAY / BUS / BOARD(첫 승차 + 대기) / TRANSFER(갈아타기 + 대기)
    line: str | None   # 식별자. 버스는 '100100017' 이라 그대로 보여주면 안 된다
    seconds: int
    stops: int         # 정차 수 (탈것만)
    distance_m: float  # 도보만
    to_name: str | None
    line_name: str | None = None   # 표시용. 없으면 `line` 으로 떨어진다

    @property
    def label(self) -> str | None:
        return self.line_name or self.line


@dataclass
class Route:
    total_sec: int
    legs: list[Leg]

    @property
    def transfers(self) -> int:
        """환승 횟수. 탈것을 갈아탄 횟수이므로 처음 탄 것은 세지 않는다."""
        rides = [l for l in self.legs if l.kind in RIDE_KINDS]
        return max(0, len(rides) - 1)

    @property
    def walk_distance_m(self) -> float:
        return sum(l.distance_m for l in self.legs if l.kind == "WALK")

    def summary(self) -> str:
        lines = [l.label for l in self.legs if l.kind in RIDE_KINDS and l.label]
        route = " → ".join(lines) if lines else "도보"
        return (f"{self.total_sec // 60}분 {self.total_sec % 60}초 · "
                f"환승 {self.transfers} · 도보 {self.walk_distance_m:.0f}m · {route}")


class Graph:
    """메모리에 올린 그래프. 탐색은 전부 여기서 한다 — DB 는 보관용이다."""

    def __init__(self, adj, node_meta, stop_index, build_id, route_names=None):
        self.adj = adj
        self.meta = node_meta            # node_id -> (kind, mode, line, name)
        self.stop_index = stop_index     # (mode, 이름) -> STOP node_id
        self.build_id = build_id
        self.route_names = route_names or {}   # (mode, line) -> 표시 이름

    @classmethod
    def load(cls, conn, build_id: int | None = None) -> "Graph":
        with conn.cursor() as cur:
            if build_id is None:
                cur.execute("SELECT id FROM graph_build WHERE is_active")
                row = cur.fetchone()
                if not row:
                    raise RuntimeError("활성 그래프 빌드가 없다. python -m etl.build_graph 먼저")
                build_id = row[0]

            cur.execute(
                """
                SELECT from_node_id, to_node_id, weight_sec, weight_by_hour, kind, line
                FROM graph_edge WHERE build_id = %s
                """,
                (build_id,),
            )
            adj = defaultdict(list)
            for f, t, w, by_hour, kind, line in cur.fetchall():
                adj[f].append((t, w, by_hour, kind, line))

            cur.execute(
                """
                SELECT n.id, n.kind, s.mode, n.line, s.name
                FROM graph_node n LEFT JOIN transit_stop s ON s.id = n.stop_id
                WHERE n.build_id = %s AND n.kind <> 'WALK'
                """,
                (build_id,),
            )
            meta, index = {}, {}
            for nid, kind, mode, line, name in cur.fetchall():
                meta[nid] = (kind, mode, line, name)
                if kind == "STOP":
                    index[(mode, name)] = nid

            # 노선 이름표. 탐색에는 안 쓰이고 결과를 읽을 때만 쓴다.
            cur.execute("SELECT mode, source_id, name FROM transit_route")
            names = {(m, sid): nm for m, sid, nm in cur.fetchall()}

        return cls(adj, meta, index, build_id, names)

    def stop(self, name: str, mode: str = "SUBWAY") -> int:
        nid = self.stop_index.get((mode, name))
        if nid is None:
            raise KeyError(f"{mode} 정류장을 찾지 못했다: {name}")
        return nid

    def snap(self, conn, lng: float, lat: float) -> tuple[int, float]:
        """좌표를 가장 가까운 보행 노드에 붙인다. `(node_id, 거리 m)`."""
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT n.id, ST_Distance(n.geom, q.g)
                FROM graph_node n
                CROSS JOIN LATERAL (
                    SELECT ST_Transform(ST_SetSRID(ST_MakePoint(%s, %s), 4326), 5186) AS g
                ) q
                WHERE n.build_id = %s AND n.kind = 'WALK'
                  AND ST_DWithin(n.geom, q.g, 2000)
                ORDER BY n.geom <-> q.g LIMIT 1
                """,
                (lng, lat, self.build_id),
            )
            row = cur.fetchone()
        if not row:
            raise RuntimeError("2km 안에 보행 노드가 없다")
        return row[0], float(row[1])

    def edge_weight(self, weight: int, by_hour, hour: int | None) -> int:
        """시간대가 주어지면 배열에서 고른다. 없으면 기본 가중치.

        음수(NO_SERVICE)는 그 시간대에 운행하지 않는다는 뜻이다. 부르는 쪽이 건너뛴다.
        """
        if hour is None or by_hour is None:
            return weight
        return int(by_hour[hour])


def dijkstra(graph: Graph, src: int, targets: set[int], hour: int | None = None):
    """단일 출발지 탐색. **목표를 전부 확정하면 멈춘다**.

    후보가 여럿이어도 탐색은 한 번이다 — 다익스트라는 출발지에서 가까운 순서로
    노드를 확정해 나가므로, 가장 먼 목표까지만 퍼뜨리면 나머지는 가는 길에 이미 나온다.
    one-to-one 을 목표 수만큼 반복하면 앞부분을 매번 다시 계산하게 된다.
    """
    dist = {src: 0}
    prev: dict[int, tuple[int, str, str | None, int]] = {}
    remaining = set(targets)
    seen = 0
    pq = [(0, src)]

    while pq and remaining:
        d, u = heapq.heappop(pq)
        if d > dist.get(u, 1 << 60):
            continue
        seen += 1
        remaining.discard(u)
        for v, w, by_hour, kind, line in graph.adj[u]:
            cost = graph.edge_weight(w, by_hour, hour)
            if cost < 0:
                continue  # 이 시간대에는 운행하지 않는다
            nd = d + cost
            if nd < dist.get(v, 1 << 60):
                dist[v] = nd
                prev[v] = (u, kind, line, nd - d)
                heapq.heappush(pq, (nd, v))

    return dist, prev, seen


def build_route(graph: Graph, prev, dist, dst: int) -> Route | None:
    """역추적한 엣지들을 사람이 읽는 구간으로 묶는다. 서버 RouteBuilder 와 같은 규칙이다.

    - BOARD(승강장까지 + 대기)와 TRANSFER(환승 통로 + 대기)는 **각자 한 줄**이다. 탈것 줄은
      주행만 담는다 — 얹으면 "2정차 10분"처럼 정차 수와 시간이 맞지 않는 줄이 나온다.
    - 이미 한 번 탔다면 BOARD 엣지로 갈아타도 TRANSFER 줄이다. 버스끼리는 원래 BOARD 로만
      갈아탄다.
    - ALIGHT(승강장에서 올라오기)는 **이어지는 줄**에 붙인다. 이어지는 줄이 없으면 마지막 줄에.
    """
    if dst not in dist:
        return None

    steps = []
    cur = dst
    while cur in prev:
        u, kind, line, w = prev[cur]
        steps.append((kind, line, w, cur))
        cur = u
    steps.reverse()

    legs: list[Leg] = []
    alighting = 0     # 아직 어느 줄에도 붙이지 않은 ALIGHT 시간
    for kind, line, w, node in steps:
        meta = graph.meta.get(node)
        mode = meta[1] if meta else None
        name = meta[3] if meta else None
        # 식별자(`line`)는 그대로 두고 표시용 이름만 얹는다 — 노선을 가르는 키는 `line` 이다.
        # 승차·환승 줄의 이름표는 타려는 노선이다.
        line_name = graph.route_names.get((mode, line)) if line else None

        if kind in ("WALK", "ACCESS"):
            meters = w * 1.2           # 거리는 걷는 시간에서만 — 올라오는 시간은 거리가 아니다
            w += alighting
            alighting = 0
            if legs and legs[-1].kind == "WALK":
                last = legs[-1]
                legs[-1] = Leg("WALK", None, last.seconds + w, 0,
                               last.distance_m + meters, name or last.to_name)
            else:
                legs.append(Leg("WALK", None, w, 0, meters, name))
        elif kind == "RIDE":
            label = "SUBWAY" if mode == "SUBWAY" else "BUS"
            if legs and legs[-1].kind == label and legs[-1].line == line:
                last = legs[-1]
                legs[-1] = replace(last, seconds=last.seconds + w, stops=last.stops + 1, to_name=name)
            else:
                legs.append(Leg(label, line, w, 1, 0.0, name, line_name))
        elif kind in BOARDING_KINDS:
            rode = any(l.kind in RIDE_KINDS for l in legs)
            label = "TRANSFER" if kind == "TRANSFER" or rode else "BOARD"
            legs.append(Leg(label, line, w + alighting, 0, 0.0, name, line_name))
            alighting = 0
        else:  # ALIGHT
            alighting += w
    if alighting and legs:
        legs[-1] = replace(legs[-1], seconds=legs[-1].seconds + alighting)
    return Route(dist[dst], legs)


def route_between(graph: Graph, src: int, dst: int, hour: int | None = None) -> Route | None:
    dist, prev, _ = dijkstra(graph, src, {dst}, hour)
    return build_route(graph, prev, dist, dst)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="그래프 경로탐색 (검증용)")
    parser.add_argument("origin", nargs="?", help="출발 역/정류장 이름")
    parser.add_argument("destination", nargs="?", help="도착 역/정류장 이름")
    parser.add_argument("--from-coord", help="lng,lat")
    parser.add_argument("--to-coord", help="lng,lat")
    parser.add_argument("--mode", default="SUBWAY", choices=["SUBWAY", "BUS"])
    parser.add_argument("--hour", type=int, default=None, help="출발 시각대 0~23")
    args = parser.parse_args(argv)

    with connect() as conn:
        graph = Graph.load(conn)
        print(f"빌드 #{graph.build_id} · 노드 {len(graph.adj):,}")

        def resolve(name, coord):
            if coord:
                lng, lat = (float(x) for x in coord.split(","))
                nid, d = graph.snap(conn, lng, lat)
                return nid, f"({lng},{lat}) 스냅 {d:.0f}m"
            return graph.stop(name, args.mode), name

        try:
            src, src_label = resolve(args.origin, args.from_coord)
            dst, dst_label = resolve(args.destination, args.to_coord)
        except KeyError as e:
            print(e, file=sys.stderr)
            return 2

        route = route_between(graph, src, dst, args.hour)

    if route is None:
        print(f"{src_label} → {dst_label}: 도달 불가")
        return 1

    hour_note = f" ({args.hour}시대)" if args.hour is not None else ""
    print(f"\n{src_label} → {dst_label}{hour_note}")
    print(f"  {route.summary()}\n")
    labels = {"WALK": "도보", "SUBWAY": "지하철", "BUS": "버스", "BOARD": "승차", "TRANSFER": "환승"}
    for leg in route.legs:
        head = f"  {labels[leg.kind]:<4} {leg.seconds:>5}초"
        if leg.kind == "WALK":
            print(f"{head}  {leg.distance_m:>5.0f}m")
        elif leg.kind in RIDE_KINDS:
            print(f"{head}  {leg.label or '':<10} {leg.stops}정차 → {leg.to_name or ''}")
        else:
            print(f"{head}  {leg.label or '':<10} @ {leg.to_name or ''}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
