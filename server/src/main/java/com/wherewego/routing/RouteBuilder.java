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
 * <h2>대기와 환승은 어디에 붙는가</h2>
 *
 * BOARD(대기)와 TRANSFER(환승 도보 + 대기)는 <b>뒤따르는 탈것 구간에 얹는다</b>. "2호선 20분"
 * 안에 기다린 시간이 들어 있다는 뜻이다. 따로 "대기 3분" 줄을 두면 줄 수만 늘고, 사용자가
 * 알고 싶은 것은 "이 노선을 타면 총 얼마"이기 때문이다.
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
        for (int i = 0; i < edges.size(); i++) {
            int edge = edges.get(i);
            int node = nodes.get(i);
            var kind = graph.edgeKind(edge);
            // 가중치를 다시 계산하지 않고 확정 거리의 차이로 구한다 — 탐색이 실제로 더한 값과
            // 표시하는 값이 어긋나면 구간 합이 총합과 맞지 않는다(시간대 가중치에서 특히).
            int seconds = result.dist()[node] - result.dist()[i == 0 ? source : nodes.get(i - 1)];
            String name = graph.stopNameOf(node);

            if (kind.isWalking()) {
                appendWalk(legs, seconds, graph.distanceM(edge), name);
            } else if (kind == EdgeKind.RIDE) {
                appendRide(graph, legs, edge, seconds, name);
            } else if (kind.isBoarding()) {
                // 대기·환승은 다음 탈것의 머리다. 노선 이름을 여기서 이미 붙인다.
                legs.add(new Leg(
                        rideKind(graph, edge),
                        graph.routeIdOf(edge),
                        graph.routeNameOf(edge),
                        seconds,
                        0,
                        0.0,
                        name));
            } else if (!legs.isEmpty()) {
                // ALIGHT — 내리는 시간은 방금 탄 구간에 더한다
                legs.set(legs.size() - 1, legs.getLast().plusSeconds(seconds, name));
            }
        }

        return new Route(result.dist()[destination], mergeAdjacent(legs));
    }

    private static void appendWalk(List<Leg> legs, int seconds, double distanceM, String name) {
        if (!legs.isEmpty() && !legs.getLast().isRide()) {
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
        return mode == TransitMode.BUS ? "BUS" : "SUBWAY";
    }

    /** 같은 종류·같은 노선이 이어지면 하나로. 대기 구간과 탑승 구간이 여기서 합쳐진다. */
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
