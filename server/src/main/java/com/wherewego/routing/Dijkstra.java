package com.wherewego.routing;

import com.wherewego.graph.TransitGraph;

/**
 * 단일 출발지 최단시간 탐색.
 *
 * <h2>왜 one-to-many 인가</h2>
 *
 * 이 서비스의 실제 질문은 "출발지 한 곳에서 후보 N 곳까지"다. 다익스트라는 출발지에서
 * <b>가까운 순서대로</b> 노드를 확정해 나가므로, 가장 먼 후보까지 한 번 퍼뜨리면 가까운 후보들은
 * 가는 길에 이미 확정되어 있다. one-to-one 을 N 번 돌리면 매번 앞부분을 다시 계산한다.
 *
 * <p>그래서 목표를 집합으로 받고, <b>목표를 전부 확정하면 그 자리에서 멈춘다</b>.
 * 서울 전체를 끝까지 훑지 않기 위한 장치다.
 *
 * <h2>비용</h2>
 *
 * 호출마다 노드 수만 한 배열 셋({@code dist}, {@code prevNode}, {@code prevEdge})을 잡는다.
 * 21만 노드면 한 번에 2.5MB 다. 재사용하면 아끼지만 그만큼 호출 간에 상태를 들고 있어야 하고,
 * 동시 요청에서 서로를 덮어쓴다. 지금은 <b>호출마다 새로 잡아 서로 간섭하지 않는 쪽</b>을 택했다.
 */
public final class Dijkstra {

    /** 시간대를 쓰지 않는다는 표시. 엣지의 기본 가중치를 쓴다. */
    public static final int NO_HOUR = -1;

    /** 닿지 못한 노드의 거리. */
    public static final int UNREACHED = Integer.MAX_VALUE;

    private Dijkstra() {}

    /**
     * @param dist 노드별 최소 소요시간(초). 닿지 못했으면 {@link #UNREACHED}
     * @param prevNode 역추적용 직전 노드. 없으면 -1
     * @param prevEdge 역추적용 직전 엣지. 없으면 -1
     * @param settled 확정한 노드 수. 조기 종료가 실제로 먹었는지 보는 값이다
     */
    public record Result(int[] dist, int[] prevNode, int[] prevEdge, int settled) {

        public boolean reached(int node) {
            return dist[node] != UNREACHED;
        }
    }

    /**
     * @param source 출발 노드
     * @param targets 도착 후보. 비어 있으면 닿는 곳을 전부 훑는다
     * @param hour 출발 시각대 0~23, 또는 {@link #NO_HOUR}
     */
    public static Result run(TransitGraph graph, int source, int[] targets, int hour) {
        int n = graph.nodeCount();
        var dist = new int[n];
        var prevNode = new int[n];
        var prevEdge = new int[n];
        java.util.Arrays.fill(dist, UNREACHED);
        java.util.Arrays.fill(prevNode, -1);
        java.util.Arrays.fill(prevEdge, -1);

        boolean[] wanted = null;
        int remaining = 0;
        if (targets != null && targets.length > 0) {
            wanted = new boolean[n];
            for (int t : targets) {
                if (t >= 0 && !wanted[t]) {
                    wanted[t] = true;
                    remaining++;
                }
            }
        }

        var queue = new LongMinHeap(1024);
        dist[source] = 0;
        queue.push(LongMinHeap.pack(0, source));

        int settled = 0;
        while (!queue.isEmpty()) {
            long top = queue.pop();
            int u = LongMinHeap.nodeOf(top);
            int d = LongMinHeap.distanceOf(top);

            // 더 짧은 길로 이미 확정된 노드다. 힙에서 원소를 지우는 대신 여기서 흘려보낸다 —
            // 힙에 있는 항목을 찾아 고치려면 위치를 따로 관리해야 하고, 그 비용이 더 크다.
            if (d > dist[u]) continue;
            settled++;

            if (wanted != null && wanted[u]) {
                wanted[u] = false;
                if (--remaining == 0) break;
            }

            int end = graph.edgeEnd(u);
            for (int e = graph.edgeStart(u); e < end; e++) {
                int v = graph.edgeTarget(e);
                int nd = d + graph.weight(e, hour);
                if (nd < dist[v]) {
                    dist[v] = nd;
                    prevNode[v] = u;
                    prevEdge[v] = e;
                    queue.push(LongMinHeap.pack(nd, v));
                }
            }
        }

        return new Result(dist, prevNode, prevEdge, settled);
    }
}
