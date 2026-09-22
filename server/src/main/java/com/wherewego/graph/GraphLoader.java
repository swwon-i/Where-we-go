package com.wherewego.graph;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 활성 빌드를 통째로 읽어 {@link TransitGraph} 를 만든다.
 *
 * <p>탐색은 DB 를 건드리지 않는다. DB 는 그래프의 보관처일 뿐이고, 한 번 읽어 메모리에 올린 뒤로는
 * 질의가 나가지 않는다 — 출발지 스냅만 예외다(공간 인덱스가 필요하다).
 *
 * <h2>커서로 읽는 이유</h2>
 *
 * PostgreSQL JDBC 드라이버는 기본적으로 <b>결과 전체를 클라이언트 메모리에 받아 둔다</b>.
 * 엣지 60만 행에 시간대별 가중치 배열까지 붙으면 그 순간 수백 MB 가 뜬다. 정작 우리가 만들
 * 최종 구조는 20MB 남짓인데 중간 단계에서 터지는 셈이다.
 *
 * <p>{@code fetchSize} 를 주고 <b>트랜잭션 안에서</b> 읽으면 드라이버가 커서를 쓴다.
 * 트랜잭션 밖에서는 fetchSize 가 무시되므로 둘은 같이 가야 한다.
 */
@Component
public class GraphLoader {

    /** 한 번에 끌어올 행 수. 왕복 횟수와 메모리의 절충. */
    private static final int FETCH_SIZE = 10_000;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public GraphLoader(DataSource dataSource, PlatformTransactionManager txManager) {
        // 공용 JdbcTemplate 의 fetchSize 를 바꾸면 다른 조회까지 영향을 받는다. 전용으로 하나 만든다.
        this.jdbc = new JdbcTemplate(dataSource);
        this.jdbc.setFetchSize(FETCH_SIZE);
        this.tx = new TransactionTemplate(txManager);
        this.tx.setReadOnly(true);
    }

    /** 활성 빌드 번호. 없으면 -1. */
    public long activeBuildId() {
        var ids = jdbc.queryForList("SELECT id FROM graph_build WHERE is_active", Long.class);
        return ids.isEmpty() ? -1 : ids.getFirst();
    }

    /**
     * 활성 빌드를 읽는다.
     *
     * @throws IllegalStateException 활성 빌드가 없을 때
     */
    public TransitGraph load() {
        long buildId = activeBuildId();
        if (buildId < 0) {
            throw new IllegalStateException("활성 그래프 빌드가 없다. python -m etl.build_graph 먼저");
        }
        return tx.execute(status -> loadBuild(buildId));
    }

    private TransitGraph loadBuild(long buildId) {
        var routes = loadRoutes();
        var nodes = loadNodes(buildId, routes);
        return loadEdges(buildId, routes, nodes);
    }

    // ── 노선 이름표 ─────────────────────────────────────────────────────────

    /**
     * 노선 식별자 → 첨자. 식별자와 표시 이름이 두 배열에 나란히 들어간다.
     *
     * <p>{@code transit_route} 에 없는 노선도 <b>식별자만 가진 항목으로 등록한다</b>.
     * 이름표가 없다고 식별자까지 잃으면 경로 상세의 노선 칸이 통째로 비어 버린다 —
     * 읽기 나쁜 것과 정보가 없는 것은 다르다.
     */
    private static final class Routes {

        private final Map<String, Integer> index = new HashMap<>();
        private final List<String> ids = new ArrayList<>();
        private final List<String> names = new ArrayList<>();

        int intern(byte mode, String routeId) {
            if (routeId == null || mode == TransitMode.NONE) return -1;
            String key = TransitMode.of(mode).name() + '\u0000' + routeId;
            Integer at = index.get(key);
            if (at != null) return at;

            // 이름표 없음. routeNameOf 가 null 을 돌려주고 표시는 식별자로 떨어진다.
            index.put(key, ids.size());
            ids.add(routeId);
            names.add(null);
            return ids.size() - 1;
        }

        private final List<String> types = new ArrayList<>();

        void put(String mode, String routeId, String name, String type) {
            index.put(mode + '\u0000' + routeId, ids.size());
            ids.add(routeId);
            names.add(name);
            types.add(type);
        }

        String[] types() {
            // intern() 이 이름 없이 붙인 노선만큼 뒤에 비어 있다. 길이를 ids 에 맞춘다.
            while (types.size() < ids.size()) types.add(null);
            return types.toArray(String[]::new);
        }

        String[] ids() {
            return ids.toArray(String[]::new);
        }

        String[] names() {
            return names.toArray(String[]::new);
        }
    }

    private Routes loadRoutes() {
        var routes = new Routes();
        jdbc.query(
                "SELECT mode, source_id, name, route_type FROM transit_route",
                rs -> {
                    routes.put(
                            rs.getString("mode"), rs.getString("source_id"), rs.getString("name"),
                            rs.getString("route_type"));
                });
        return routes;
    }

    // ── 노드 ────────────────────────────────────────────────────────────────

    private record Nodes(
            long[] dbId,
            byte[] kind,
            byte[] mode,
            int[] stop,
            int[] route,
            float[] lng,
            float[] lat,
            String[] stopNames,
            Map<String, Integer> stopIndex) {}

    private Nodes loadNodes(long buildId, Routes routes) {
        int n = count("SELECT count(*) FROM graph_node WHERE build_id = ?", buildId);

        var dbId = new long[n];
        var kind = new byte[n];
        var mode = new byte[n];
        var stop = new int[n];
        var route = new int[n];
        var lng = new float[n];
        var lat = new float[n];

        var nameIndex = new HashMap<String, Integer>();
        var nameList = new ArrayList<String>();
        var stopIndex = new HashMap<String, Integer>();

        // ORDER BY id — 첨자를 오름차순으로 매겨야 나중에 이진 탐색으로 되찾을 수 있다.
        var counter = new int[1];
        jdbc.query(
                """
                SELECT n.id, n.kind, n.line, s.mode, s.name,
                       ST_X(ST_Transform(n.geom, 4326)) AS lng,
                       ST_Y(ST_Transform(n.geom, 4326)) AS lat
                FROM graph_node n
                LEFT JOIN transit_stop s ON s.id = n.stop_id
                WHERE n.build_id = ?
                ORDER BY n.id
                """,
                rs -> {
                    int i = counter[0]++;
                    dbId[i] = rs.getLong("id");
                    kind[i] = NodeKind.valueOf(rs.getString("kind")).code();
                    mode[i] = TransitMode.codeOf(rs.getString("mode"));
                    lng[i] = (float) rs.getDouble("lng");
                    lat[i] = (float) rs.getDouble("lat");

                    String name = rs.getString("name");
                    stop[i] = name == null
                            ? -1
                            : nameIndex.computeIfAbsent(name, k -> {
                                nameList.add(k);
                                return nameList.size() - 1;
                            });
                    route[i] = routes.intern(mode[i], rs.getString("line"));

                    if (kind[i] == NodeKind.STOP.code() && name != null) {
                        stopIndex.put(TransitMode.of(mode[i]).name() + '\u0000' + name, i);
                    }
                },
                buildId);

        if (counter[0] != n) {
            throw new IllegalStateException(
                    "노드 수가 세는 사이에 바뀌었다: " + n + " → " + counter[0]);
        }
        return new Nodes(
                dbId, kind, mode, stop, route, lng, lat, nameList.toArray(String[]::new), stopIndex);
    }

    // ── 엣지 ────────────────────────────────────────────────────────────────

    private TransitGraph loadEdges(long buildId, Routes routes, Nodes nodes) {
        int e = count("SELECT count(*) FROM graph_edge WHERE build_id = ?", buildId);
        int n = nodes.dbId().length;

        // 일단 읽은 순서대로 담는다. CSR 로 줄 세우는 것은 다 읽은 뒤에 한다 —
        // DB 에 ORDER BY 를 맡기면 60만 행을 정렬하느라 시간을 쓴다.
        var from = new int[e];
        var to = new int[e];
        var weight = new int[e];
        var kind = new byte[e];
        var route = new int[e];
        var distCm = new int[e];
        var hourlyAt = new int[e];
        // 시간대별 가중치가 있는 엣지만 뒤에 이어 붙인다. 대부분의 엣지(보행)는 없다.
        var hourlyBuf = new ArrayList<short[]>();

        var counter = new int[1];
        jdbc.query(
                """
                SELECT from_node_id, to_node_id, kind, weight_sec, weight_by_hour,
                       line, distance_m
                FROM graph_edge WHERE build_id = ?
                """,
                rs -> {
                    int i = counter[0]++;
                    int f = indexOf(nodes.dbId(), rs.getLong("from_node_id"));
                    int t = indexOf(nodes.dbId(), rs.getLong("to_node_id"));
                    if (f < 0 || t < 0) {
                        throw new IllegalStateException("엣지가 없는 노드를 가리킨다: " + i);
                    }
                    from[i] = f;
                    to[i] = t;
                    weight[i] = rs.getInt("weight_sec");
                    kind[i] = EdgeKind.valueOf(rs.getString("kind")).code();

                    // 노선의 수단은 엣지에 없다. 플랫폼 쪽 끝 노드에서 가져온다.
                    byte m = nodes.mode()[t] != TransitMode.NONE ? nodes.mode()[t] : nodes.mode()[f];
                    route[i] = routes.intern(m, rs.getString("line"));

                    float d = rs.getFloat("distance_m");
                    distCm[i] = rs.wasNull() ? 0 : Math.round(d * 100);

                    short[] hours = readHours(rs);
                    if (hours == null) {
                        hourlyAt[i] = -1;
                    } else {
                        hourlyAt[i] = hourlyBuf.size() * 24;
                        hourlyBuf.add(hours);
                    }
                },
                buildId);

        if (counter[0] != e) {
            throw new IllegalStateException("엣지 수가 세는 사이에 바뀌었다: " + e + " → " + counter[0]);
        }

        var hourly = new short[hourlyBuf.size() * 24];
        for (int i = 0; i < hourlyBuf.size(); i++) {
            System.arraycopy(hourlyBuf.get(i), 0, hourly, i * 24, 24);
        }

        var csr = toCsr(n, from, to, weight, kind, route, distCm, hourlyAt);

        return new TransitGraph(
                buildId,
                nodes.dbId(),
                nodes.kind(),
                nodes.mode(),
                nodes.stop(),
                nodes.route(),
                nodes.lng(),
                nodes.lat(),
                csr.head(),
                csr.to(),
                csr.weight(),
                csr.kind(),
                csr.route(),
                csr.distCm(),
                csr.hourlyAt(),
                hourly,
                nodes.stopNames(),
                routes.ids(),
                routes.names(),
                routes.types(),
                nodes.stopIndex());
    }

    record Csr(
            int[] head, int[] to, int[] weight, byte[] kind, int[] route,
            int[] distCm, int[] hourlyAt) {}

    /**
     * 엣지를 출발 노드 순으로 줄 세운다 — 계수 정렬이라 한 번만 훑는다.
     *
     * <p>비교 정렬을 쓰면 60만 × log(60만) 인데, 키가 0..N-1 정수라 그럴 필요가 없다.
     * 노드별 개수를 세고, 누적합으로 각자의 시작 위치를 정하고, 다시 한 번 훑으며 제자리에 넣는다.
     */
    static Csr toCsr(
            int n, int[] from, int[] to, int[] weight, byte[] kind, int[] route,
            int[] distCm, int[] hourlyAt) {
        int e = from.length;
        var head = new int[n + 1];
        for (int i = 0; i < e; i++) {
            head[from[i] + 1]++;
        }
        for (int u = 0; u < n; u++) {
            head[u + 1] += head[u];
        }

        var pos = head.clone();
        var oTo = new int[e];
        var oWeight = new int[e];
        var oKind = new byte[e];
        var oRoute = new int[e];
        var oDist = new int[e];
        var oHourly = new int[e];
        for (int i = 0; i < e; i++) {
            int p = pos[from[i]]++;
            oTo[p] = to[i];
            oWeight[p] = weight[i];
            oKind[p] = kind[i];
            oRoute[p] = route[i];
            oDist[p] = distCm[i];
            oHourly[p] = hourlyAt[i];
        }
        return new Csr(head, oTo, oWeight, oKind, oRoute, oDist, oHourly);
    }

    private static short[] readHours(ResultSet rs) throws SQLException {
        var array = rs.getArray("weight_by_hour");
        if (array == null) return null;
        var boxed = (Short[]) array.getArray();
        var out = new short[24];
        for (int h = 0; h < 24; h++) {
            out[h] = boxed[h] == null ? 0 : boxed[h];
        }
        return out;
    }

    private static int indexOf(long[] sortedDbIds, long id) {
        int at = Arrays.binarySearch(sortedDbIds, id);
        return at < 0 ? -1 : at;
    }

    private int count(String sql, long buildId) {
        Integer n = jdbc.queryForObject(sql, Integer.class, buildId);
        return n == null ? 0 : n;
    }
}
