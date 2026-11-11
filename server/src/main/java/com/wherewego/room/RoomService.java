package com.wherewego.room;

import com.wherewego.account.AccountService;
import com.wherewego.graph.GraphCache;
import com.wherewego.graph.TransitMode;
import com.wherewego.routing.RouteService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
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

    /** 방의 인원이나 후보가 상한에 닿았다. */
    public static class LimitReachedException extends RuntimeException {
        public LimitReachedException(String message) {
            super(message);
        }
    }

    /**
     * 방 하나의 최대 인원.
     *
     * <p>행렬은 사람마다 탐색을 한 번씩 순서대로 돈다 — 인원이 곧 요청 하나의 무게다. 운영
     * 서버(e2-small)에서 후보가 서울 전역에 흩어진 최악의 경우 사람 하나에 약 235ms 라, 상한이
     * 없으면 큰 방 하나가 vCPU 하나를 수십 초 붙잡는다(docs/perf-server.md). 10 명이면 최악이
     * 약 2.4초이고, 성능 측정이 가정한 "10명 × 20곳"이 서비스의 실제 최악값이 된다.
     */
    public static final int MAX_MEMBERS = 10;

    /**
     * 방 하나의 최대 후보 수.
     *
     * <p>후보는 인원만큼 무겁지 않다. one-to-many 라 후보가 늘어도 탐색은 사람마다 한 번이고, 그
     * 한 번의 비용은 그래프 크기로 막혀 있다. 대신 행렬 요청마다 <b>후보 하나당 스냅 질의 한 번</b>,
     * <b>칸(인원 × 후보)마다 경로 되짚기 한 번</b>이 들고 응답도 그만큼 커진다. 30 곳이면 행렬이
     * 최대 10 × 30 = 300 칸이다.
     */
    public static final int MAX_BOOKMARKS = 30;

    /** 초대 코드가 겹쳤을 때 다시 뽑는 횟수. 32^8 중 하나라 한 번도 안 돌 것이다. */
    private static final int CODE_ATTEMPTS = 5;

    @Transactional
    public UUID create(String title) {
        var me = accounts.currentUser();
        var roomId = withFreshCode(code -> repository.createRoom(title.strip(), me.id(), code));
        // 만든 사람은 자동으로 참가한다. 따로 들어가게 할 이유가 없다.
        repository.join(roomId, me.id());
        return roomId;
    }

    /**
     * 초대 코드로 참가한다.
     *
     * @return 들어간 방의 id
     * @throws NotFoundException 코드 형식이 틀렸거나 그런 방이 없을 때.
     *     <b>둘을 가르지 않는다</b> — 형식만 맞으면 "없는 코드"라고 알려주는 것은
     *     찍어 보는 쪽에 유효한 코드를 좁혀 주는 일이다
     */
    @Transactional
    public UUID joinByCode(String rawCode) {
        String code = InviteCode.normalize(rawCode);
        var room = (code == null ? Optional.<RoomRepository.RoomRow>empty() : repository.findByCode(code))
                .orElseThrow(() -> new NotFoundException("그런 초대 코드가 없습니다"));

        enter(room.id());
        return room.id();
    }

    /**
     * 처음 들어올 때만 활동이다. 재입장은 아무것도 바꾸지 않는다 — 꽉 찬 방이어도 이미 참가자면
     * 들어온다. 자리를 더 쓰지 않으니까.
     *
     * <h2>방 행을 먼저 잠근다</h2>
     *
     * 인원을 세고 넣는 사이에 다른 사람이 들어오면 둘 다 9 를 보고 둘 다 들어온다. 잠그기 전에는
     * 빈자리 하나에 8명이 동시에 들어오자 8명이 모두 들어가 17명이 됐다(RoomCapacityTest).
     * {@code FOR UPDATE} 로 방 행을 잡으면 <b>같은 방으로 들어오는 참가만</b> 한 줄로 서고,
     * 다른 방의 참가는 서로 기다리지 않는다. 잠금은 이 트랜잭션이 끝날 때 풀린다.
     *
     * @throws LimitReachedException 새로 들어오는데 {@link #MAX_MEMBERS} 명이 이미 있을 때
     * @throws NotFoundException 잠그려는 사이에 방이 지워졌을 때
     */
    private void enter(UUID roomId) {
        long me = accounts.currentUser().id();
        if (!repository.lockRoom(roomId)) throw new NotFoundException("없는 방입니다");
        if (repository.isMember(roomId, me)) return;
        if (repository.memberCount(roomId) >= MAX_MEMBERS) {
            throw new LimitReachedException("방이 꽉 찼습니다 (최대 " + MAX_MEMBERS + "명)");
        }
        if (repository.join(roomId, me) > 0) {
            repository.touchActivity(roomId);
        }
    }

    /**
     * 초대 코드를 새로 뽑는다. 방장만.
     *
     * <p>코드가 새어 나갔을 때 되돌릴 방법이 필요하다. 링크만 있던 구조에서는 그것이 불가능했다 —
     * 주소를 바꾸면 이미 들어온 사람들의 북마크가 가리키는 방도 함께 사라지기 때문이다.
     * 코드는 문이 하나 더 있는 것이라 그 문만 바꿀 수 있다.
     */
    @Transactional
    public String regenerateCode(UUID roomId) {
        var me = accounts.currentUser();
        var room = repository.findRoom(roomId).orElseThrow(() -> new NotFoundException("없는 방입니다"));
        if (room.ownerId() != me.id()) {
            throw new ForbiddenException("방을 만든 사람만 코드를 바꿀 수 있습니다");
        }
        return withFreshCode(code -> {
            repository.updateCode(roomId, code);
            return code;
        });
    }

    /** 코드를 뽑아 넘기고, 겹치면 다시 뽑는다. */
    private <T> T withFreshCode(java.util.function.Function<String, T> action) {
        for (int attempt = 1; ; attempt++) {
            try {
                return action.apply(InviteCode.generate());
            } catch (DuplicateKeyException e) {
                if (attempt >= CODE_ATTEMPTS) throw e;
            }
        }
    }

    @Transactional
    public void join(UUID roomId) {
        repository.findRoom(roomId).orElseThrow(() -> new NotFoundException("없는 방입니다"));
        enter(roomId);
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
                room.inviteCode(),
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
        repository.touchActivity(roomId);

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

    /**
     * 후보 자리가 남았는지 본다. 인원 상한과 같은 이유로 방 행을 먼저 잠근다 — 29 곳인 방에 여럿이
     * 동시에 담으면 다 같이 29 를 보고 다 들어간다({@link #enter} 참고).
     *
     * @throws LimitReachedException {@link #MAX_BOOKMARKS} 곳이 이미 있을 때
     */
    private void reserveBookmarkSlot(UUID roomId) {
        if (!repository.lockRoom(roomId)) throw new NotFoundException("없는 방입니다");
        if (repository.bookmarkCount(roomId) >= MAX_BOOKMARKS) {
            throw new LimitReachedException(
                    "후보는 한 방에 " + MAX_BOOKMARKS + "곳까지 담을 수 있습니다. 하나를 빼고 담아 주세요");
        }
    }

    /** 우리 POI 에서 고른다. 이름·좌표는 이 시점의 값으로 복사된다. */
    @Transactional
    public long addBookmarkFromPoi(UUID roomId, long poiId) {
        var me = requireMember(roomId);
        var poi = repository
                .poiSnapshot(poiId)
                .orElseThrow(() -> new NotFoundException("없는 장소입니다: " + poiId));
        reserveBookmarkSlot(roomId);
        long bookmarkId = repository.addBookmark(
                roomId, me, poiId, poi.name(), poi.address(), poi.lng(), poi.lat());
        repository.touchActivity(roomId);
        return bookmarkId;
    }

    /** 지도를 찍어 직접 넣는다. 우리 POI 에 없는 곳도 후보가 될 수 있어야 한다. */
    @Transactional
    public long addBookmarkAtPoint(UUID roomId, String name, double lng, double lat) {
        var me = requireMember(roomId);
        reserveBookmarkSlot(roomId);
        long bookmarkId = repository.addBookmark(roomId, me, null, name.strip(), null, lng, lat);
        repository.touchActivity(roomId);
        return bookmarkId;
    }

    @Transactional
    public void removeBookmark(UUID roomId, long bookmarkId) {
        var me = requireMember(roomId);
        if (repository.deleteBookmark(roomId, bookmarkId, me) == 0) {
            // 0건이면 없는 것이거나 남의 것이다. 어느 쪽인지 알려줘야 화면이 설명할 수 있다.
            if (repository.bookmarkExists(roomId, bookmarkId)) {
                throw new ForbiddenException("담은 사람이나 방장만 뺄 수 있습니다");
            }
            throw new NotFoundException("없는 북마크입니다");
        }
        repository.touchActivity(roomId);
    }

    private long requireMember(UUID roomId) {
        var me = accounts.currentUser();
        if (!repository.isMember(roomId, me.id())) {
            throw new NotAMemberException("먼저 방에 들어가야 합니다");
        }
        return me.id();
    }

    // ── 나가기 · 삭제 ───────────────────────────────────────────────────────

    /**
     * 방을 없앤다. 방장만.
     *
     * <p><b>나가기와 다른 뜻이다.</b> 나가기는 "나만 빠진다"이고 이것은 "이 모임을 끝낸다"이다.
     * 남이 담은 후보까지 함께 사라지는데, 그것은 방을 만든 사람에게 있는 권한으로 본다.
     *
     * <p>계산 기록({@code matrix_run})은 남는다 — 외래키를 걸지 않은 것이 그 때문이다.
     */
    @Transactional
    public void deleteRoom(UUID roomId) {
        var me = accounts.currentUser();
        var room = repository.findRoom(roomId).orElseThrow(() -> new NotFoundException("없는 방입니다"));
        if (room.ownerId() != me.id()) {
            throw new ForbiddenException("방을 만든 사람만 방을 지울 수 있습니다");
        }
        repository.deleteRoom(roomId);
    }

    /** 이 방에서 나만 뺀다. */
    @Transactional
    public void leave(UUID roomId) {
        repository.findRoom(roomId).orElseThrow(() -> new NotFoundException("없는 방입니다"));
        leaveRoom(roomId, accounts.currentUser().id());
    }

    /**
     * 탈퇴할 때 쓴다. 들어가 있는 모든 방에서 같은 규칙으로 빠진다.
     *
     * <p>세션이 아니라 <b>id 를 받는다</b> — 부르는 쪽이 탈퇴 처리 중이라 "지금 로그인한
     * 사람"으로 찾으면 안 되고, 그래야 account 패키지에서 거꾸로 부를 수 있다.
     */
    @Transactional
    public void leaveAllRooms(long userId) {
        for (var roomId : repository.roomIdsOf(userId)) {
            leaveRoom(roomId, userId);
        }
    }

    /**
     * 한 사람이 한 방에서 빠지는 규칙. 나가기와 탈퇴가 이것 하나를 같이 쓴다.
     *
     * <ul>
     *   <li>마지막 한 명이면 방이 지워진다 — 아무도 없는 방을 남길 이유가 없다
     *   <li>방장이면 가장 먼저 들어온 참가자에게 넘기고 나간다
     *   <li>담아 둔 후보는 남는다. 모임의 공동 작업이지 내 소유물이 아니다
     * </ul>
     */
    private void leaveRoom(UUID roomId, long userId) {
        if (!repository.isMember(roomId, userId)) {
            throw new NotAMemberException("이 방의 참가자가 아닙니다");
        }
        if (repository.memberCount(roomId) <= 1) {
            repository.deleteRoom(roomId);
            return;
        }
        var room = repository.findRoom(roomId).orElseThrow(() -> new NotFoundException("없는 방입니다"));
        if (room.ownerId() == userId) {
            // 참가자가 둘 이상인 것을 위에서 확인했으므로 반드시 있다.
            long next = repository
                    .nextOwnerAfter(roomId, userId)
                    .orElseThrow(() -> new IllegalStateException("넘겨받을 참가자가 없습니다"));
            repository.transferOwner(roomId, next);
        }
        repository.removeMember(roomId, userId);
        repository.touchActivity(roomId);
    }
}
