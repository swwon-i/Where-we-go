package com.wherewego.matrix;

import com.wherewego.room.RoomService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 이동시간 행렬과 칸 하나의 경로 상세.
 *
 * <pre>
 *   POST /api/v1/rooms/{roomId}/matrix   {departureHour?}
 *   GET  /api/v1/rooms/{roomId}/routes?originMemberId=&amp;bookmarkId=&amp;departureHour=
 * </pre>
 *
 * <p>상세는 좌표를 다시 받아 재계산하지 않고 <b>행렬과 같은 입력·같은 그래프 빌드에서 재현</b>한다.
 * 좌표를 다시 받는 방식은 동률 경로에서 행렬 칸과 상세가 어긋날 수 있다.
 */
@RestController
@RequestMapping("/api/v1/rooms/{roomId}")
public class MatrixController {

    private final MatrixService matrices;

    public MatrixController(MatrixService matrices) {
        this.matrices = matrices;
    }

    /** @param departureHour 0~23. 없으면 기본 19시 */
    public record MatrixRequest(Integer departureHour) {}

    @PostMapping("/matrix")
    public Matrix compute(
            @PathVariable UUID roomId,
            @RequestBody(required = false) MatrixRequest request) {

        Integer hour = request == null ? null : request.departureHour();
        return matrices.compute(roomId, validateHour(hour));
    }

    @GetMapping("/routes")
    public MatrixService.RouteDetail route(
            @PathVariable UUID roomId,
            @RequestParam long originMemberId,
            @RequestParam long bookmarkId,
            @RequestParam(required = false) Integer departureHour) {

        return matrices.routeDetail(roomId, originMemberId, bookmarkId, validateHour(departureHour));
    }

    private static Integer validateHour(Integer hour) {
        if (hour == null) return null;
        if (hour < 0 || hour > 23) {
            throw new IllegalArgumentException("departureHour 는 0~23 입니다: " + hour);
        }
        return hour;
    }

    // ── 실패 ────────────────────────────────────────────────────────────────

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(RoomService.NotAMemberException.class)
    ResponseEntity<Map<String, String>> notAMember(RoomService.NotAMemberException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(RoomService.NotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(RoomService.NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
    }

    /**
     * 계산할 재료가 없다.
     *
     * <p>422 다. 요청 자체는 올바르고(400 이 아니고) 권한도 있는데, 방의 <b>상태</b>가 아직
     * 계산할 수 있는 상태가 아니라는 뜻이다.
     */
    @ExceptionHandler(MatrixService.NotComputableException.class)
    ResponseEntity<Map<String, String>> notComputable(MatrixService.NotComputableException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(Map.of("message", e.getMessage()));
    }

    /** 방 단위 동시 계산 1건 제한. */
    @ExceptionHandler(MatrixService.AlreadyRunningException.class)
    ResponseEntity<Map<String, String>> alreadyRunning(MatrixService.AlreadyRunningException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of("message", e.getMessage()));
    }

    /** 그래프가 아직 안 올라왔다. */
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, String>> graphMissing(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("message", e.getMessage()));
    }
}
