package com.wherewego.room;

import com.wherewego.routing.RouteService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 방 관련 실패를 화면이 쓸 수 있는 모양으로. */
@RestControllerAdvice(assignableTypes = RoomController.class)
class RoomExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e) {
        var fields = new LinkedHashMap<String, String>();
        for (var error : e.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        return ResponseEntity.badRequest()
                .body(Map.of("message", "입력을 확인해 주세요", "fields", fields));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(RoomService.NotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(RoomService.NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
    }

    /** 참가자가 아니다. 404 가 아니라 403 이다 — 방이 있다는 것 자체는 링크를 받은 사람이 안다. */
    @ExceptionHandler(RoomService.NotAMemberException.class)
    ResponseEntity<Map<String, String>> notAMember(RoomService.NotAMemberException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(RoomService.ForbiddenException.class)
    ResponseEntity<Map<String, String>> forbidden(RoomService.ForbiddenException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
    }

    /**
     * 같은 장소를 두 번 담았다.
     *
     * <p>먼저 확인하지 않고 제약에 맡긴다. 확인과 INSERT 사이에 다른 사람이 같은 곳을 담을 수
     * 있어 어차피 제약이 최종 방어선이고, 이 경우는 조용히 실패해도 될 만큼 사소하다.
     */
    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<Map<String, String>> duplicate(DuplicateKeyException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("message", "이미 담은 장소입니다"));
    }

    /** 출발지를 보행망에 붙이지 못했다. 계획서의 ORIGIN_UNREACHABLE(409) 자리다. */
    @ExceptionHandler(RouteService.NoSuchNodeException.class)
    ResponseEntity<Map<String, String>> unreachable(RouteService.NoSuchNodeException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
    }

    /** 그래프가 아직 안 올라왔다. 방과 북마크는 되지만 출발지 스냅은 못 한다. */
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, String>> graphMissing(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("message", e.getMessage()));
    }
}
