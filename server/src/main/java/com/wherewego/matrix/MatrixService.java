package com.wherewego.matrix;

import com.wherewego.account.AccountService;
import com.wherewego.graph.GraphCache;
import com.wherewego.graph.TransitGraph;
import com.wherewego.room.RoomRepository;
import com.wherewego.room.RoomService;
import com.wherewego.room.RoomView;
import com.wherewego.routing.Dijkstra;
import com.wherewego.routing.Route;
import com.wherewego.routing.RouteBuilder;
import com.wherewego.routing.RouteService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * 출발지 × 후보 이동시간 행렬.
 *
 * <h2>출발지마다 한 번만 탐색한다</h2>
 *
 * 다익스트라는 출발지에서 <b>가까운 순서대로</b> 노드를 확정해 나간다. 가장 먼 후보까지 한 번
 * 퍼뜨리면 가까운 후보들은 가는 길에 이미 확정되어 있다. 후보 수만큼 one-to-one 을 돌리면
 * 매번 앞부분을 다시 계산한다.
 *
 * <p>그래서 {@code dijkstraRuns} 가 <b>출발지 수와 같고 후보 수와 무관</b>하다. 이것이
 * one-to-many 로 돌고 있다는 증거이고, {@code stats} 에 그대로 내보낸다.
 *
 * <h2>동기 계산이다</h2>
 *
 * 비동기 잡과 폴링을 두지 않는다. 출발지 5~10명 규모면 수초 안에 끝나므로, 진행률을 보여주자고
 * 상태 기계를 하나 더 만들 이유가 없다. 대신 <b>방 단위로 한 번에 하나만</b> 돌린다 —
 * 링크만 알면 누구나 부를 수 있는 엔드포인트라 최소한의 남용 방어가 필요하다.
 */
@Service
public class MatrixService {

    /** 시간대를 주지 않았을 때. 계획서 §4.4 의 기본값이다. */
    public static final int DEFAULT_HOUR = 19;

    private final RoomRepository rooms;
    private final AccountService accounts;
    private final GraphCache graphs;
    private final RouteService routes;

    /** 지금 계산 중인 방. 같은 방이 겹쳐 들어오면 429 로 돌려보낸다. */
    private final Map<UUID, Boolean> running = new ConcurrentHashMap<>();

    public MatrixService(
            RoomRepository rooms,
            AccountService accounts,
            GraphCache graphs,
            RouteService routes) {
        this.rooms = rooms;
        this.accounts = accounts;
        this.graphs = graphs;
        this.routes = routes;
    }

    /** 이미 같은 방을 계산하고 있다. */
    public static class AlreadyRunningException extends RuntimeException {
        public AlreadyRunningException() {
            super("이 방의 행렬을 이미 계산하고 있습니다. 잠시 후 다시 시도해 주세요");
        }
    }

    /** 계산할 재료가 없다 — 출발지가 없거나 후보가 없다. */
    public static class NotComputableException extends RuntimeException {
        public NotComputableException(String message) {
            super(message);
        }
    }

    public Matrix compute(UUID roomId, Integer departureHour) {
        if (running.putIfAbsent(roomId, Boolean.TRUE) != null) {
            throw new AlreadyRunningException();
        }
        try {
            return computeInternal(roomId, departureHour);
        } finally {
            running.remove(roomId);
        }
    }

    // @Transactional 을 걸지 않는다. compute() 에서 부르는 자기 호출이라 프록시를 타지 않아
    // 붙여도 동작하지 않는다. 쓰기는 재스냅 한 건뿐이고 그 UPDATE 는 그 자체로 원자적이다.
    private Matrix computeInternal(UUID roomId, Integer departureHour) {
        long startedAt = System.nanoTime();

        var me = accounts.currentUser();
        if (!rooms.isMember(roomId, me.id())) {
            throw new RoomService.NotAMemberException("먼저 방에 들어가야 합니다");
        }

        // 그래프를 이미 들고 있었는지 **읽기 전에** 본다. get() 이 적재해 버리면 구분이 사라진다.
        boolean wasCached = graphs.status().loaded();
        var graph = graphs.get();

        var originRows = rooms.originsOf(roomId);
        var bookmarks = rooms.bookmarks(roomId, me.id());
        if (originRows.isEmpty()) {
            throw new NotComputableException("출발지를 정한 사람이 없습니다");
        }
        if (bookmarks.isEmpty()) {
            throw new NotComputableException("후보 장소가 없습니다");
        }

        int hour = departureHour == null ? DEFAULT_HOUR : departureHour;

        var origins = resolveOrigins(graph, originRows);
        var destinations = resolveDestinations(graph, bookmarks);

        // 목표를 한 배열로 모은다. 붙이지 못한 후보는 빠지므로 탐색이 더 일찍 끝난다.
        int[] targets = destinations.stream()
                .filter(d -> d.node() >= 0)
                .mapToInt(Destination::node)
                .toArray();

        var rowCells = new ArrayList<List<Matrix.Cell>>();
        for (var ignored : destinations) rowCells.add(new ArrayList<>());

        int expanded = 0;
        for (var origin : origins) {
            var result = Dijkstra.run(graph, origin.node(), targets, hour);
            expanded += result.settled();

            for (int d = 0; d < destinations.size(); d++) {
                rowCells.get(d).add(cellFor(graph, result, destinations.get(d)));
            }
        }

        var rows = new ArrayList<Matrix.Row>(destinations.size());
        for (int d = 0; d < destinations.size(); d++) {
            var dest = destinations.get(d);
            rows.add(new Matrix.Row(
                    dest.bookmarkId(), dest.name(), dest.address(),
                    dest.lng(), dest.lat(), dest.snapM(), List.copyOf(rowCells.get(d))));
        }

        long elapsed = (System.nanoTime() - startedAt) / 1_000_000;
        return new Matrix(
                graph.buildId(),
                departureHour,
                origins.stream()
                        .map(o -> new Matrix.Origin(
                                o.memberId(), o.nickname(), o.label(), o.snapM(), o.resnapped()))
                        .toList(),
                rows,
                new Matrix.Stats(
                        origins.size(),
                        expanded,
                        elapsed,
                        wasCached ? "CACHE" : "DB",
                        graph.buildId(),
                        origins.size(),
                        destinations.size()));
    }

    // ── 칸 하나의 상세 ──────────────────────────────────────────────────────

    /**
     * 행렬의 칸 하나를 눌렀을 때 보여줄 경로.
     *
     * @param legs 도보 · 승차(승강장까지 + 대기) · 주행 · 환승(갈아타기 + 대기)이 각자 한 줄이다. RouteBuilder 참조
     */
    public record RouteDetail(
            long graphBuildId,
            Integer departureHour,
            String originNickname,
            String originLabel,
            String destinationName,
            boolean reachable,
            String reason,
            Integer totalSeconds,
            Integer transfers,
            Double walkDistanceM,
            String summary,
            List<Leg> legs) {

        /**
         * @param label 화면에 쓸 노선 이름. 이름표가 없으면 식별자로 떨어진다
         * @param routeType 버스 노선 유형. 지도 선 색을 고른다
         * @param path 지도에 그릴 좌표열 {@code [[경도, 위도], …]}. {@link com.wherewego.routing.Leg#path} 참조
         */
        public record Leg(
                String kind, String line, String label,
                int seconds, int stops, double distanceM, String toName,
                String routeType, List<double[]> path) {}
    }

    /**
     * 행렬과 <b>같은 입력·같은 그래프</b>에서 다시 구한다.
     *
     * <p>좌표를 다시 받아 계산하면 동률 경로에서 행렬 칸과 상세가 어긋날 수 있다.
     * 저장된 출발지와 후보를 그대로 쓰고 시간대만 같이 맞춘다.
     */
    public RouteDetail routeDetail(
            UUID roomId, long originMemberId, long bookmarkId, Integer departureHour) {

        var me = accounts.currentUser();
        if (!rooms.isMember(roomId, me.id())) {
            throw new RoomService.NotAMemberException("먼저 방에 들어가야 합니다");
        }

        var graph = graphs.get();
        int hour = departureHour == null ? DEFAULT_HOUR : departureHour;

        var origin = resolveOrigins(graph, rooms.originsOf(roomId)).stream()
                .filter(o -> o.memberId() == originMemberId)
                .findFirst()
                .orElseThrow(() -> new RoomService.NotFoundException("그 참가자의 출발지가 없습니다"));

        var destination = resolveDestinations(graph, rooms.bookmarks(roomId, me.id())).stream()
                .filter(d -> d.bookmarkId() == bookmarkId)
                .findFirst()
                .orElseThrow(() -> new RoomService.NotFoundException("없는 후보입니다"));

        if (destination.node() < 0) {
            return unreachableDetail(graph, origin, destination, departureHour, "NO_WALK_NETWORK");
        }

        var result = Dijkstra.run(graph, origin.node(), new int[] {destination.node()}, hour);
        var route = RouteBuilder.build(graph, result, destination.node());
        if (route == null) {
            return unreachableDetail(graph, origin, destination, departureHour, "UNREACHABLE");
        }

        return new RouteDetail(
                graph.buildId(),
                departureHour,
                origin.nickname(),
                origin.label(),
                destination.name(),
                true,
                null,
                route.totalSeconds(),
                route.transfers(),
                route.walkDistanceM(),
                route.summary(),
                route.legs().stream()
                        .map(l -> new RouteDetail.Leg(
                                l.kind(), l.line(), l.label(),
                                l.seconds(), l.stops(), l.distanceM(), l.toName(),
                                l.routeType(), l.path()))
                        .toList());
    }

    private RouteDetail unreachableDetail(
            TransitGraph graph, ResolvedOrigin origin, Destination destination,
            Integer departureHour, String reason) {

        return new RouteDetail(
                graph.buildId(), departureHour,
                origin.nickname(), origin.label(), destination.name(),
                false, reason, null, null, null, null, List.of());
    }

    // ── 출발지 ──────────────────────────────────────────────────────────────

    private record ResolvedOrigin(
            long memberId, String nickname, String label,
            int node, double snapM, boolean resnapped) {}

    /**
     * 저장된 노드를 그래프 첨자로 옮긴다. 그래프가 바뀌었으면 다시 붙인다.
     *
     * <p>{@code graph_node.id} 는 빌드마다 새로 발급되므로 옛 빌드의 노드 번호는 지금 그래프에
     * 없거나, 있어도 <b>엉뚱한 자리</b>다. 후자가 더 위험하다 — 조용히 틀린 답이 나온다.
     * 그래서 번호를 못 찾았을 때만이 아니라 <b>빌드 번호가 다르면 무조건</b> 다시 붙인다.
     */
    private List<ResolvedOrigin> resolveOrigins(
            TransitGraph graph, List<RoomRepository.OriginRow> rows) {

        var out = new ArrayList<ResolvedOrigin>(rows.size());
        for (var row : rows) {
            boolean stale = row.buildId() != graph.buildId();
            int node = stale ? -1 : graph.indexOf(row.nodeId());

            if (node < 0) {
                var snapped = routes.atCoordinate(graph, row.lng(), row.lat());
                rooms.resnapOrigin(
                        row.memberId(),
                        graph.dbIdOf(snapped.node()),
                        snapped.snapDistanceM(),
                        graph.buildId());
                out.add(new ResolvedOrigin(
                        row.memberId(), row.nickname(), row.label(),
                        snapped.node(), snapped.snapDistanceM(), true));
            } else {
                out.add(new ResolvedOrigin(
                        row.memberId(), row.nickname(), row.label(), node, row.snapM(), false));
            }
        }
        return out;
    }

    // ── 후보 ────────────────────────────────────────────────────────────────

    /** @param node 그래프 첨자. 붙이지 못했으면 -1 */
    private record Destination(
            long bookmarkId, String name, String address,
            double lng, double lat, int node, Double snapM) {}

    /**
     * 후보를 보행망에 붙인다.
     *
     * <p>붙이지 못해도 <b>그 줄만</b> 실패다. 후보 하나가 서울 밖이라고 행렬 전체를 버리면
     * 나머지 후보를 비교할 기회까지 사라진다.
     *
     * <p>스냅 결과를 저장하지 않고 매번 구한다. 후보는 열 개 남짓이고 공간 인덱스를 타므로
     * 싸며, 저장하면 출발지처럼 <b>빌드가 바뀌었는지 따라다녀야 하는 상태</b>가 하나 더 는다.
     */
    private List<Destination> resolveDestinations(
            TransitGraph graph, List<RoomView.BookmarkView> bookmarks) {

        var out = new ArrayList<Destination>(bookmarks.size());
        for (var b : bookmarks) {
            try {
                var snapped = routes.atCoordinate(graph, b.lng(), b.lat());
                out.add(new Destination(
                        b.bookmarkId(), b.name(), b.address(), b.lng(), b.lat(),
                        snapped.node(), snapped.snapDistanceM()));
            } catch (RuntimeException e) {
                out.add(new Destination(
                        b.bookmarkId(), b.name(), b.address(), b.lng(), b.lat(), -1, null));
            }
        }
        return out;
    }

    private Matrix.Cell cellFor(
            TransitGraph graph, Dijkstra.Result result, Destination destination) {

        if (destination.node() < 0) return Matrix.Cell.unreachable("NO_WALK_NETWORK");

        Route route = RouteBuilder.build(graph, result, destination.node());
        if (route == null) return Matrix.Cell.unreachable("UNREACHABLE");

        return new Matrix.Cell(
                true,
                route.totalSeconds(),
                route.transfers(),
                route.walkDistanceM(),
                route.summary(),
                null);
    }
}
