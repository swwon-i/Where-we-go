package com.wherewego.room;

import com.wherewego.account.AccountService;
import com.wherewego.graph.GraphCache;
import com.wherewego.graph.TransitMode;
import com.wherewego.routing.RouteService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 방을 만들고, 들어가고, 출발지와 후보를 채운다. */
@Service
public class RoomService {

    private final RoomRepository repository;
    private final AccountService accounts;
    private final GraphCache graphs;
    private final RouteService routes;

    public RoomService(
            RoomRepository repository,
            AccountService accounts,
            GraphCache graphs,
            RouteService routes) {
        this.repository = repository;
        this.accounts = accounts;
        this.graphs = graphs;
        this.routes = routes;
    }

    /** 없는 방이거나 볼 권한이 없다. 둘을 가르지 않는다 — 방이 있는지조차 알려줄 이유가 없다. */
    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    /** 방 안의 일인데 참가자가 아니다. */
    public static class NotAMemberException extends RuntimeException {
        public NotAMemberException(String message) {
            super(message);
        }
    }

    /** 남의 것을 건드리려 했다. */
    public static class ForbiddenException extends RuntimeException {
        public ForbiddenException(String message) {
            super(message);
        }
    }

    @Transactional
    public UUID create(String title) {
        var me = accounts.currentUser();
        var roomId = repository.createRoom(title.strip(), me.id());
        // 만든 사람은 자동으로 참가한다. 따로 들어가게 할 이유가 없다.
        repository.join(roomId, me.id());
        return roomId;
    }

    @Transactional
    public void join(UUID roomId) {
        repository.findRoom(roomId).orElseThrow(() -> new NotFoundException("없는 방입니다"));
        repository.join(roomId, accounts.currentUser().id());
    }

    /** 내가 참가한 방 목록. */
    public List<RoomRepository.RoomRow> myRooms() {
        return repository.roomsOf(accounts.currentUser().id());
    }

    /**
     * 방 전체 상태.
     *
     * <p>참가자가 아니면 볼 수 없다. 링크를 아는 사람은 <b>들어온 뒤에</b> 보게 된다 —
     * 방 링크는 초대장이지 열람권이 아니다.
     */
    public RoomView view(UUID roomId) {
        var me = accounts.currentUser();
        var room = repository.findRoom(roomId).orElseThrow(() -> new NotFoundException("없는 방입니다"));
        if (!repository.isMember(roomId, me.id())) {
            throw new NotAMemberException("먼저 방에 들어가야 합니다");
        }

        // 그래프가 아직 없어도 방은 보여야 한다. 스냅이 지금 그래프와 맞는지만 못 따질 뿐이다.
        long activeBuild = graphs.status().buildId();

        var members = repository.members(roomId, me.id(), activeBuild);
        var bookmarks = repository.bookmarks(roomId, me.id());

        return new RoomView(
                room.id(),
                room.title(),
                room.ownerNickname(),
                room.ownerId() == me.id(),
                room.createdAt(),
                members,
                bookmarks,
                members.stream().anyMatch(RoomView.MemberView::originSet) && !bookmarks.isEmpty());
    }

    /**
     * 내 출발지를 정한다.
     *
     * @param label 사용자가 본 이름. 좌표만 남기면 나중에 "여기가 어디였더라"가 된다
     * @return 스냅 결과. 숨기지 않고 그대로 돌려준다
     */
    @Transactional
    public OriginResult setMyOrigin(UUID roomId, double lng, double lat, String label) {
        var me = accounts.currentUser();
        long memberId = repository
                .memberId(roomId, me.id())
                .orElseThrow(() -> new NotAMemberException("먼저 방에 들어가야 합니다"));

        var graph = routes.graph();
        var snapped = routes.atCoordinate(graph, lng, lat);
        long nodeDbId = graph.dbIdOf(snapped.node());

        repository.setOrigin(
                memberId, label, lng, lat, nodeDbId, snapped.snapDistanceM(), graph.buildId());

        return new OriginResult(
                memberId, label, lng, lat, nodeDbId, snapped.snapDistanceM(), graph.buildId());
    }

    /** 지하철역 이름으로 출발지를 정한다. 역 좌표를 찾아 좌표 방식으로 넘긴다. */
    @Transactional
    public OriginResult setMyOriginByStation(UUID roomId, String stationName) {
        var graph = routes.graph();
        int stop = graph.findStop(TransitMode.SUBWAY, stationName);
        if (stop < 0) {
            throw new NotFoundException("그런 역이 없습니다: " + stationName);
        }
        var point = routes.coordinateOf(graph.dbIdOf(stop));
        return setMyOrigin(roomId, point[0], point[1], stationName);
    }

    /**
     * @param snapDistanceM 보행망까지 걸어야 하는 거리. 크면 결과를 덜 믿어야 한다는 신호다
     * @param graphBuildId 이 스냅이 어느 그래프에서 나왔는지. 달라지면 다시 붙여야 한다
     */
    public record OriginResult(
            long memberId,
            String label,
            double lng,
            double lat,
            long graphNodeId,
            double snapDistanceM,
            long graphBuildId) {}

    // ── 북마크 ──────────────────────────────────────────────────────────────

    /** 우리 POI 에서 고른다. 이름·좌표는 이 시점의 값으로 복사된다. */
    @Transactional
    public long addBookmarkFromPoi(UUID roomId, long poiId) {
        var me = requireMember(roomId);
        var poi = repository
                .poiSnapshot(poiId)
                .orElseThrow(() -> new NotFoundException("없는 장소입니다: " + poiId));
        return repository.addBookmark(
                roomId, me, poiId, poi.name(), poi.address(), poi.lng(), poi.lat());
    }

    /** 지도를 찍어 직접 넣는다. 우리 POI 에 없는 곳도 후보가 될 수 있어야 한다. */
    @Transactional
    public long addBookmarkAtPoint(UUID roomId, String name, double lng, double lat) {
        var me = requireMember(roomId);
        return repository.addBookmark(roomId, me, null, name.strip(), null, lng, lat);
    }

    @Transactional
    public void removeBookmark(UUID roomId, long bookmarkId) {
        var me = requireMember(roomId);
        if (repository.deleteBookmark(roomId, bookmarkId, me) == 0) {
            // 0건이면 없는 것이거나 남의 것이다. 어느 쪽인지 알려줘야 화면이 설명할 수 있다.
            if (repository.bookmarkExists(roomId, bookmarkId)) {
                throw new ForbiddenException("담은 사람만 지울 수 있습니다");
            }
            throw new NotFoundException("없는 북마크입니다");
        }
    }

    private long requireMember(UUID roomId) {
        var me = accounts.currentUser();
        if (!repository.isMember(roomId, me.id())) {
            throw new NotAMemberException("먼저 방에 들어가야 합니다");
        }
        return me.id();
    }
}
