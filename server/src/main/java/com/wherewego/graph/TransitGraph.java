package com.wherewego.graph;

import java.util.Arrays;
import java.util.Map;

/**
 * 메모리에 올린 탐색용 그래프. 한 번 만들면 바뀌지 않는다.
 *
 * <h2>왜 객체가 아니라 배열인가 (CSR)</h2>
 *
 * 노드 21만, 엣지 60만이다. 인접리스트를 {@code Map<Node, List<Edge>>} 로 들면 엣지 하나가
 * 객체 헤더 16바이트 + 필드 + 리스트 노드까지 60바이트를 넘고, 무엇보다 <b>이웃을 훑을 때
 * 메모리를 널뛰며 읽는다</b>. 다익스트라는 이웃 순회가 내부 루프라 이 비용이 그대로 나온다.
 *
 * <p>여기서는 CSR(Compressed Sparse Row)을 쓴다. 엣지를 출발 노드 순으로 한 줄로 정렬해 두고,
 * {@code head[u]}부터 {@code head[u+1]} 까지가 {@code u} 의 이웃이다.
 *
 * <pre>
 *   head   [0, 3, 3, 5, ...]      노드마다 자기 엣지가 시작하는 위치
 *   edgeTo [7, 2, 9, 4, 1, ...]   이웃이 연속으로 붙어 있다
 * </pre>
 *
 * 이웃 순회가 연속된 배열 읽기가 되어 캐시에 잘 맞고, 객체 헤더가 없어 메모리도 훨씬 적다.
 * 대신 만든 뒤에는 엣지를 못 넣는다 — 그래프를 빌드 단위로 통째로 교체하는 이 구조에서는
 * 잃을 게 없는 제약이다.
 *
 * <p>잰 값(docs/perf.md, 빌드 #17, 같은 탐색을 {@code List<List<Edge>>} 로 돌린 대조군):
 * 탐색 구조가 엣지당 16.3B 대 48.6B 로 <b>3.0배</b> 작고, 서울 전체 탐색은 74.8ms 대 89.6ms 로
 * <b>1.2배</b> 빠르다. 시간 차이가 메모리 차이보다 작은 것은 두 쪽이 같은 힙을 쓰기 때문이다 —
 * 힙 연산은 표현과 무관하다.
 *
 * <h2>노드 번호</h2>
 *
 * DB 의 {@code graph_node.id} 는 빌드마다 이어지는 값이라 0부터 촘촘하지 않다. 배열 첨자로
 * 쓰려면 0..N-1 로 다시 매겨야 한다. {@code nodeDbId} 를 <b>오름차순으로</b> 채워 두고
 * 이진 탐색으로 되찾는다 — 21만 개짜리 {@code HashMap<Long,Integer>} 를 들지 않기 위해서다.
 */
public final class TransitGraph {

    private final long buildId;

    // ── 노드 ────────────────────────────────────────────────────────────────
    private final long[] nodeDbId;   // 오름차순. 이진 탐색으로 DB id → 첨자
    private final byte[] nodeKind;
    private final byte[] nodeMode;   // TransitMode.code() 또는 NONE
    private final int[] nodeStop;    // stopNames 첨자, 없으면 -1
    private final int[] nodeRoute;   // routeNames 첨자, 없으면 -1
    private final float[] nodeLng;   // WGS84. 경로를 지도에 그릴 때만 쓴다
    private final float[] nodeLat;

    // ── 엣지 (CSR) ──────────────────────────────────────────────────────────
    private final int[] head;        // 길이 N+1
    private final int[] edgeTo;
    private final int[] edgeWeight;  // 초
    private final byte[] edgeKind;
    private final int[] edgeRoute;   // routeNames 첨자, 없으면 -1
    private final int[] edgeDistCm;  // 거리(cm). 도보만 의미가 있다
    private final int[] edgeHourly;  // hourly 안의 시작 위치, 없으면 -1
    private final short[] hourly;    // 24개씩 이어 붙인 시간대별 가중치

    // ── 이름표 ──────────────────────────────────────────────────────────────
    private final String[] stopNames;
    private final String[] routeIds;
    private final String[] routeNames;
    private final String[] routeTypes;  // 버스 노선 유형(간선·지선…). 지하철은 null, 급행은 '급행'
    private final Map<String, Integer> stopIndex;  // "SUBWAY\u0000강남" → 노드 첨자

    TransitGraph(
            long buildId,
            long[] nodeDbId,
            byte[] nodeKind,
            byte[] nodeMode,
            int[] nodeStop,
            int[] nodeRoute,
            float[] nodeLng,
            float[] nodeLat,
            int[] head,
            int[] edgeTo,
            int[] edgeWeight,
            byte[] edgeKind,
            int[] edgeRoute,
            int[] edgeDistCm,
            int[] edgeHourly,
            short[] hourly,
            String[] stopNames,
            String[] routeIds,
            String[] routeNames,
            String[] routeTypes,
            Map<String, Integer> stopIndex) {
        this.buildId = buildId;
        this.nodeDbId = nodeDbId;
        this.nodeKind = nodeKind;
        this.nodeMode = nodeMode;
        this.nodeStop = nodeStop;
        this.nodeRoute = nodeRoute;
        this.nodeLng = nodeLng;
        this.nodeLat = nodeLat;
        this.head = head;
        this.edgeTo = edgeTo;
        this.edgeWeight = edgeWeight;
        this.edgeKind = edgeKind;
        this.edgeRoute = edgeRoute;
        this.edgeDistCm = edgeDistCm;
        this.edgeHourly = edgeHourly;
        this.hourly = hourly;
        this.stopNames = stopNames;
        this.routeIds = routeIds;
        this.routeNames = routeNames;
        this.routeTypes = routeTypes;
        this.stopIndex = stopIndex;
    }

    public long buildId() {
        return buildId;
    }

    public int nodeCount() {
        return nodeKind.length;
    }

    public int edgeCount() {
        return edgeTo.length;
    }

    // ── 이웃 순회 ───────────────────────────────────────────────────────────

    /** {@code node} 의 엣지가 시작하는 위치. {@code edgeEnd} 까지가 그 노드의 이웃이다. */
    public int edgeStart(int node) {
        return head[node];
    }

    public int edgeEnd(int node) {
        return head[node + 1];
    }

    public int edgeTarget(int edge) {
        return edgeTo[edge];
    }

    public EdgeKind edgeKind(int edge) {
        return EdgeKind.of(edgeKind[edge]);
    }

    /**
     * 시간대 배열에서 "이 시간에는 운행하지 않는다"를 뜻하는 값. ETL 의 {@code NO_SERVICE} 와 같다.
     *
     * <p>0 이 아니라 음수인 이유는 0초가 "공짜로 지나간다"로 읽히기 때문이다. 이 값을 받은
     * 탐색은 그 엣지를 건너뛴다 — 새벽 0~4시에만 다니는 N버스가 낮에 다니거나, 새벽 4시에
     * 지하철이 다니는 일을 막는다.
     */
    public static final int NO_SERVICE = -1;

    /**
     * 엣지 비용(초). 그 시간대에 운행하지 않으면 {@link #NO_SERVICE}.
     *
     * @param hour 출발 시각대 0~23. 음수면 시간대를 쓰지 않는다.
     */
    public int weight(int edge, int hour) {
        if (hour < 0) return edgeWeight[edge];
        int at = edgeHourly[edge];
        return at < 0 ? edgeWeight[edge] : hourly[at + hour];
    }

    /** 도보 거리(m). 탈것 엣지는 0 이다. */
    public double distanceM(int edge) {
        return edgeDistCm[edge] / 100.0;
    }

    // ── 이름 ────────────────────────────────────────────────────────────────

    /** 노선 식별자. 버스는 '100100017' 처럼 사람이 읽을 수 없는 값이다. */
    public String routeIdOf(int edge) {
        int r = edgeRoute[edge];
        return r < 0 ? null : routeIds[r];
    }

    /** 표시용 노선 이름. 이름표가 없으면 식별자로 떨어진다. */
    /**
     * 노드의 경도·위도(WGS84). 경로를 지도에 그리는 데만 쓴다 — 탐색은 좌표를 보지 않는다.
     *
     * <p>{@code float} 로 둔다. 7자리 유효숫자면 서울에서 1m 안팎이라 선을 그리기에 충분하고,
     * 노드 21만 개에 1.7MB 로 {@code double} 의 절반이다.
     */
    public double lngOf(int node) {
        return nodeLng[node];
    }

    public double latOf(int node) {
        return nodeLat[node];
    }

    /** 버스 노선 유형(간선·지선·광역·마을·순환…). 색을 고르는 데 쓴다. 지하철이면 null. */
    public String routeTypeOf(int edge) {
        int r = edgeRoute[edge];
        return r < 0 || routeTypes == null ? null : routeTypes[r];
    }

    public String routeNameOf(int edge) {
        int r = edgeRoute[edge];
        return r < 0 ? null : routeNames[r];
    }

    public NodeKind kindOf(int node) {
        return NodeKind.of(nodeKind[node]);
    }

    public TransitMode modeOf(int node) {
        return TransitMode.of(nodeMode[node]);
    }

    /** 역·정류장 이름. 보행 노드는 null. */
    public String stopNameOf(int node) {
        int s = nodeStop[node];
        return s < 0 ? null : stopNames[s];
    }

    /** 플랫폼 노드가 속한 노선의 표시 이름. */
    public String nodeRouteNameOf(int node) {
        int r = nodeRoute[node];
        return r < 0 ? null : routeNames[r];
    }

    // ── 찾기 ────────────────────────────────────────────────────────────────

    /**
     * 이름으로 STOP 노드를 찾는다. 없으면 -1.
     *
     * <p>같은 이름의 역과 버스정류장이 따로 있으므로 수단이 키의 일부다.
     */
    public int findStop(TransitMode mode, String name) {
        Integer n = stopIndex.get(mode.name() + '\u0000' + name);
        return n == null ? -1 : n;
    }

    /** DB 의 {@code graph_node.id} → 배열 첨자. 없으면 -1. */
    public int indexOf(long dbNodeId) {
        int at = Arrays.binarySearch(nodeDbId, dbNodeId);
        return at < 0 ? -1 : at;
    }

    public long dbIdOf(int node) {
        return nodeDbId[node];
    }

    /** 대략적인 메모리 사용량(바이트). {@code /api/v1/graph} 에 노출한다. */
    public long approximateBytes() {
        return 8L * nodeDbId.length
                + nodeKind.length
                + nodeMode.length
                + 4L * (nodeStop.length + nodeRoute.length + head.length)
                + 4L * (edgeTo.length + edgeWeight.length + edgeRoute.length)
                + 4L * (edgeDistCm.length + edgeHourly.length)
                + edgeKind.length
                + 2L * hourly.length
                + 8L * nodeLng.length;
    }
}
