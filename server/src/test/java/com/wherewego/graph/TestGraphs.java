package com.wherewego.graph;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/**
 * 손으로 짠 작은 그래프. DB 없이 탐색 로직만 검증하기 위한 것이다.
 *
 * <p>실제 빌드로 도는 검증은 따로 있다({@code RouteIntegrationTest}). 둘을 나눈 이유는
 * <b>실패했을 때 어디가 틀렸는지 알기 위해서</b>다. 여기서 깨지면 로직이 틀린 것이고,
 * 저기서만 깨지면 데이터나 빌드가 틀린 것이다.
 *
 * <p>{@link TransitGraph} 의 생성자가 패키지 전용이라 이 도우미도 같은 패키지에 둔다.
 */
public final class TestGraphs {

    private TestGraphs() {}

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private record Node(long id, NodeKind kind, byte mode, String stopName, String line) {}

        private record Edge(
                long from, long to, int weight, EdgeKind kind, String line,
                double distanceM, short[] hourly) {}

        private final List<Node> nodes = new ArrayList<>();
        private final List<Edge> edges = new ArrayList<>();
        private final List<String> routeIds = new ArrayList<>();
        private final List<String> routeNames = new ArrayList<>();
        private final HashMap<String, Integer> routeIndex = new HashMap<>();

        /** 보행 노드. */
        public Builder walk(long id) {
            nodes.add(new Node(id, NodeKind.WALK, TransitMode.NONE, null, null));
            return this;
        }

        /** 역·정류장. 진출입의 문이다. */
        public Builder stop(long id, TransitMode mode, String name) {
            nodes.add(new Node(id, NodeKind.STOP, mode.code(), name, null));
            return this;
        }

        /** (정류장 × 노선). 실제로 타는 자리다. */
        public Builder platform(long id, TransitMode mode, String line, String name) {
            nodes.add(new Node(id, NodeKind.PLATFORM, mode.code(), name, line));
            return this;
        }

        /** 노선 이름표. 등록하지 않으면 식별자가 그대로 표시된다. */
        public Builder route(TransitMode mode, String id, String name) {
            routeIndex.put(mode.name() + '\u0000' + id, routeIds.size());
            routeIds.add(id);
            routeNames.add(name);
            return this;
        }

        public Builder edge(long from, long to, int weight, EdgeKind kind, String line) {
            edges.add(new Edge(from, to, weight, kind, line, 0, null));
            return this;
        }

        /** 거리가 있는 도보 엣지. */
        public Builder walkEdge(long from, long to, int weight, double distanceM) {
            edges.add(new Edge(from, to, weight, EdgeKind.WALK, null, distanceM, null));
            return this;
        }

        /** 시간대별 가중치를 가진 엣지. {@code hours} 는 24개여야 한다. */
        public Builder hourlyEdge(
                long from, long to, int weight, int[] hours, EdgeKind kind, String line) {
            if (hours.length != 24) throw new IllegalArgumentException("시간대는 24개다");
            var packed = new short[24];
            for (int h = 0; h < 24; h++) packed[h] = (short) hours[h];
            edges.add(new Edge(from, to, weight, kind, line, 0, packed));
            return this;
        }

        public TransitGraph build() {
            var sorted = nodes.stream().sorted((a, b) -> Long.compare(a.id(), b.id())).toList();
            int n = sorted.size();

            var dbId = new long[n];
            var kind = new byte[n];
            var mode = new byte[n];
            var stop = new int[n];
            var route = new int[n];
            var stopNames = new ArrayList<String>();
            var nameIndex = new HashMap<String, Integer>();
            var stopIndex = new HashMap<String, Integer>();

            for (int i = 0; i < n; i++) {
                var node = sorted.get(i);
                dbId[i] = node.id();
                kind[i] = node.kind().code();
                mode[i] = node.mode();
                stop[i] = node.stopName() == null
                        ? -1
                        : nameIndex.computeIfAbsent(node.stopName(), k -> {
                            stopNames.add(k);
                            return stopNames.size() - 1;
                        });
                route[i] = lookupRoute(node.mode(), node.line());
                if (node.kind() == NodeKind.STOP) {
                    stopIndex.put(TransitMode.of(node.mode()).name() + '\u0000' + node.stopName(), i);
                }
            }

            int e = edges.size();
            var from = new int[e];
            var to = new int[e];
            var weight = new int[e];
            var edgeKind = new byte[e];
            var edgeRoute = new int[e];
            var distCm = new int[e];
            var hourlyAt = new int[e];
            var hourlyBuf = new ArrayList<short[]>();

            for (int i = 0; i < e; i++) {
                var edge = edges.get(i);
                from[i] = indexOf(dbId, edge.from());
                to[i] = indexOf(dbId, edge.to());
                weight[i] = edge.weight();
                edgeKind[i] = edge.kind().code();
                byte m = mode[to[i]] != TransitMode.NONE ? mode[to[i]] : mode[from[i]];
                edgeRoute[i] = lookupRoute(m, edge.line());
                distCm[i] = (int) Math.round(edge.distanceM() * 100);
                if (edge.hourly() == null) {
                    hourlyAt[i] = -1;
                } else {
                    hourlyAt[i] = hourlyBuf.size() * 24;
                    hourlyBuf.add(edge.hourly());
                }
            }

            var hourly = new short[hourlyBuf.size() * 24];
            for (int i = 0; i < hourlyBuf.size(); i++) {
                System.arraycopy(hourlyBuf.get(i), 0, hourly, i * 24, 24);
            }

            var csr = GraphLoader.toCsr(n, from, to, weight, edgeKind, edgeRoute, distCm, hourlyAt);

            return new TransitGraph(
                    0,
                    dbId, kind, mode, stop, route,
                    csr.head(), csr.to(), csr.weight(), csr.kind(), csr.route(),
                    csr.distCm(), csr.hourlyAt(), hourly,
                    stopNames.toArray(String[]::new),
                    routeIds.toArray(String[]::new),
                    routeNames.toArray(String[]::new),
                    stopIndex);
        }

        /** 이름표가 없는 노선도 식별자만 가진 항목으로 등록한다. GraphLoader 와 같은 규칙이다. */
        private int lookupRoute(byte mode, String line) {
            if (line == null || mode == TransitMode.NONE) return -1;
            String key = TransitMode.of(mode).name() + '\u0000' + line;
            Integer at = routeIndex.get(key);
            if (at != null) return at;
            routeIndex.put(key, routeIds.size());
            routeIds.add(line);
            routeNames.add(null);
            return routeIds.size() - 1;
        }

        private static int indexOf(long[] dbId, long id) {
            int at = Arrays.binarySearch(dbId, id);
            if (at < 0) throw new IllegalStateException("엣지가 없는 노드를 가리킨다: " + id);
            return at;
        }
    }
}
