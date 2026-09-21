package com.wherewego.routing;

import com.wherewego.graph.EdgeKind;
import com.wherewego.graph.TransitGraph;
import com.wherewego.graph.TransitMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 역추적한 엣지들을 사람이 읽는 구간으로 묶는다.
 *
 * <p>탐색 결과는 엣지의 나열이다. 그대로 내보내면 "보행 12m, 보행 8m, 보행 31m…" 이 수백 줄
 * 나온다. 같은 성격이 이어지면 하나로 합쳐야 읽을 수 있다.
 *
 * <h2>대기와 환승은 따로 한 줄이다</h2>
 *
 * BOARD(승강장까지 + 대기)와 TRANSFER(환승 통로 + 대기)는 <b>각자 한 줄</b>로 둔다.
 * 예전에는 뒤따르는 탈것 줄에 얹었는데, 그러면 "6호선 2정차 10분"처럼 정차 수와 시간이
 * 맞지 않는 줄이 나왔다 — 10분 중 4분이 공덕 환승이었다. 탈것 줄은 주행만 담는다.
 *
 * <p>ALIGHT(승강장에서 올라오기)는 <b>이어지는 줄</b>에 붙인다. 보통은 역을 걸어 나가는
 * 도보이고, 버스를 같은 정류장에서 갈아타면 다음 승차다. 탈것 줄에 붙이면 다시 주행 외의
 * 시간이 섞인다.
 */
public final class RouteBuilder {

    private RouteBuilder() {}

    /** 닿지 못했으면 null. */
    public static Route build(TransitGraph graph, Dijkstra.Result result, int destination) {
        if (!result.reached(destination)) return null;

        // 도착지에서 거꾸로 따라가 뒤집는다
        var edges = new ArrayList<Integer>();
        var nodes = new ArrayList<Integer>();
        for (int at = destination; result.prevEdge()[at] >= 0; at = result.prevNode()[at]) {
            edges.add(result.prevEdge()[at]);
            nodes.add(at);
        }
        java.util.Collections.reverse(edges);
        java.util.Collections.reverse(nodes);

        int source = result.prevNode()[nodes.getFirst()];

        var legs = new ArrayList<Leg>();
        int alighting = 0;  // 아직 어느 줄에도 붙이지 않은 ALIGHT 시간
        for (int i = 0; i < edges.size(); i++) {
            int edge = edges.get(i);
            int node = nodes.get(i);
            var kind = graph.edgeKind(edge);
            // 가중치를 다시 계산하지 않고 확정 거리의 차이로 구한다 — 탐색이 실제로 더한 값과
            // 표시하는 값이 어긋나면 구간 합이 총합과 맞지 않는다(시간대 가중치에서 특히).
            int seconds = result.dist()[node] - result.dist()[i == 0 ? source : nodes.get(i - 1)];
            String name = graph.stopNameOf(node);

            if (kind.isWalking()) {
                appendWalk(legs, seconds + alighting, graph.distanceM(edge), name);
                alighting = 0;
            } else if (kind == EdgeKind.RIDE) {
                appendRide(graph, legs, edge, seconds, name);
            } else if (kind.isBoarding()) {
                // 이름표는 타려는 노선이다. "승차 · 5호선 · 화곡", "환승 · 6호선 · 공덕".
                //
                // 이미 한 번 탔다면 어느 엣지를 지났든 환승이다. 탐색은 환승 통로(TRANSFER)
                // 대신 대합실로 나갔다 다시 타는 길(ALIGHT → BOARD)을 고를 수 있고 — 공덕
                // 5→6호선은 1초 차이로 그쪽이 쌌다 — 버스끼리는 원래 BOARD 로만 갈아탄다.
                // 사용자에게는 모두 "갈아타는 일"이고, 환승 횟수도 같은 기준으로 센다.
                boolean alreadyRode = legs.stream().anyMatch(Leg::isRide);
                legs.add(new Leg(
                        kind == EdgeKind.TRANSFER || alreadyRode ? Leg.TRANSFER : Leg.BOARD,
                        graph.routeIdOf(edge),
                        graph.routeNameOf(edge),
                        seconds + alighting,
                        0,
                        0.0,
                        name));
                alighting = 0;
            } else {
                // ALIGHT — 이어지는 줄(역을 나가는 도보, 또는 다음 승차)에 붙인다
                alighting += seconds;
            }
        }
        if (alighting > 0 && !legs.isEmpty()) {
            // 역에서 끝나는 경로라 이어지는 줄이 없다. 마지막 줄에 붙여 합계를 맞춘다.
            legs.set(legs.size() - 1, legs.getLast().plusSeconds(alighting, null));
        }

        return new Route(result.dist()[destination], mergeAdjacent(legs));
    }

    private static void appendWalk(List<Leg> legs, int seconds, double distanceM, String name) {
        if (!legs.isEmpty() && legs.getLast().isWalk()) {
            var last = legs.removeLast();
            legs.add(new Leg(
                    Leg.WALK, null, null,
                    last.seconds() + seconds, 0,
                    last.distanceM() + distanceM,
                    name != null ? name : last.toName()));
        } else {
            legs.add(new Leg(Leg.WALK, null, null, seconds, 0, distanceM, name));
        }
    }

    private static void appendRide(
            TransitGraph graph, List<Leg> legs, int edge, int seconds, String name) {
        String kind = rideKind(graph, edge);
        String line = graph.routeIdOf(edge);
        if (!legs.isEmpty()
                && legs.getLast().kind().equals(kind)
                && java.util.Objects.equals(legs.getLast().line(), line)) {
            var last = legs.removeLast();
            legs.add(new Leg(
                    kind, line, last.lineName(),
                    last.seconds() + seconds, last.stops() + 1, 0.0, name));
        } else {
            legs.add(new Leg(kind, line, graph.routeNameOf(edge), seconds, 1, 0.0, name));
        }
    }

    /** 탈것 구간의 이름표. 지하철인지 버스인지는 도착 노드의 수단으로 정한다. */
    private static String rideKind(TransitGraph graph, int edge) {
        var mode = graph.modeOf(graph.edgeTarget(edge));
        return mode == TransitMode.BUS ? Leg.BUS : Leg.SUBWAY;
    }

    /** 같은 종류·같은 노선이 이어지면 하나로. */
    private static List<Leg> mergeAdjacent(List<Leg> legs) {
        var merged = new ArrayList<Leg>();
        for (var leg : legs) {
            if (!merged.isEmpty()
                    && merged.getLast().kind().equals(leg.kind())
                    && java.util.Objects.equals(merged.getLast().line(), leg.line())) {
                merged.add(merged.removeLast().merge(leg));
            } else {
                merged.add(leg);
            }
        }
        return List.copyOf(merged);
    }
}
