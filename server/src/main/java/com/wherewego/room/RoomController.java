package com.wherewego.room;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 방 · 참가 · 출발지 · 북마크.
 *
 * <pre>
 *   POST   /api/v1/rooms                              방 만들기
 *   GET    /api/v1/rooms                              내가 들어간 방들
 *   POST   /api/v1/rooms/{roomId}/members             참가
 *   GET    /api/v1/rooms/{roomId}                     방 상태 (화면은 이것만 본다)
 *   PUT    /api/v1/rooms/{roomId}/members/me/origin   내 출발지
 *   POST   /api/v1/rooms/{roomId}/bookmarks           후보 담기
 *   DELETE /api/v1/rooms/{roomId}/bookmarks/{id}      후보 빼기
 * </pre>
 *
 * <p>전부 로그인이 필요하다. 본인 확인은 세션이 하므로 요청에 사용자 식별자를 싣지 않는다 —
 * 실어 보내면 그 값을 바꿔 남 행세를 할 수 있는지부터 따져야 한다.
 */
@RestController
@RequestMapping("/api/v1/rooms")
public class RoomController {

    private final RoomService rooms;

    public RoomController(RoomService rooms) {
        this.rooms = rooms;
    }

    // ── 방 ──────────────────────────────────────────────────────────────────

    public record CreateRoomRequest(
            @NotBlank(message = "방 이름을 입력해 주세요")
                    @Size(max = 50, message = "방 이름은 50자까지입니다")
                    String title) {}

    public record RoomCreated(UUID roomId, String title) {}

    public record RoomSummary(UUID roomId, String title, String ownerNickname, Instant createdAt) {}

    @PostMapping
    public ResponseEntity<RoomCreated> create(@Valid @RequestBody CreateRoomRequest request) {
        var roomId = rooms.create(request.title());
        return ResponseEntity.created(URI.create("/api/v1/rooms/" + roomId))
                .body(new RoomCreated(roomId, request.title().strip()));
    }

    @GetMapping
    public List<RoomSummary> myRooms() {
        return rooms.myRooms().stream()
                .map(r -> new RoomSummary(r.id(), r.title(), r.ownerNickname(), r.createdAt()))
                .toList();
    }

    @GetMapping("/{roomId}")
    public RoomView view(@PathVariable UUID roomId) {
        return rooms.view(roomId);
    }

    /** 참가. 이미 들어가 있으면 그대로 성공이다 — 다시 누르는 것이 오류일 이유가 없다. */
    @PostMapping("/{roomId}/members")
    public RoomView join(@PathVariable UUID roomId) {
        rooms.join(roomId);
        return rooms.view(roomId);
    }

    // ── 출발지 ──────────────────────────────────────────────────────────────

    /**
     * 좌표로 주거나 역 이름으로 준다.
     *
     * @param label 화면에 보일 이름. 좌표만 남기면 나중에 본인도 어디였는지 모른다
     */
    public record OriginRequest(Double lng, Double lat, String station, String label) {}

    @PutMapping("/{roomId}/members/me/origin")
    public RoomService.OriginResult setOrigin(
            @PathVariable UUID roomId, @RequestBody OriginRequest request) {

        if (request.station() != null && !request.station().isBlank()) {
            return rooms.setMyOriginByStation(roomId, request.station().strip());
        }
        if (request.lng() == null || request.lat() == null) {
            throw new IllegalArgumentException("좌표(lng, lat) 나 역 이름(station) 중 하나는 필요합니다");
        }
        String label = request.label() == null || request.label().isBlank()
                ? "%.5f, %.5f".formatted(request.lng(), request.lat())
                : request.label().strip();
        return rooms.setMyOrigin(roomId, request.lng(), request.lat(), label);
    }

    // ── 북마크 ──────────────────────────────────────────────────────────────

    /**
     * 검색 결과에서 고르거나({@code poiId}) 지도를 찍어 직접 넣는다({@code name} + 좌표).
     */
    public record BookmarkRequest(Long poiId, String name, Double lng, Double lat) {}

    public record BookmarkCreated(long bookmarkId) {}

    @PostMapping("/{roomId}/bookmarks")
    public ResponseEntity<BookmarkCreated> addBookmark(
            @PathVariable UUID roomId, @RequestBody BookmarkRequest request) {

        long id;
        if (request.poiId() != null) {
            id = rooms.addBookmarkFromPoi(roomId, request.poiId());
        } else {
            if (request.name() == null || request.name().isBlank()
                    || request.lng() == null || request.lat() == null) {
                throw new IllegalArgumentException("poiId 또는 (name, lng, lat) 가 필요합니다");
            }
            id = rooms.addBookmarkAtPoint(roomId, request.name(), request.lng(), request.lat());
        }
        return ResponseEntity.created(URI.create("/api/v1/rooms/" + roomId + "/bookmarks/" + id))
                .body(new BookmarkCreated(id));
    }

    @DeleteMapping("/{roomId}/bookmarks/{bookmarkId}")
    public ResponseEntity<Void> removeBookmark(
            @PathVariable UUID roomId, @PathVariable long bookmarkId) {
        rooms.removeBookmark(roomId, bookmarkId);
        return ResponseEntity.noContent().build();
    }
}
