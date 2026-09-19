package com.wherewego.account;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 가입·로그인 실패를 화면이 쓸 수 있는 모양으로 바꾼다.
 *
 * <p>어느 칸이 왜 틀렸는지는 알려주되, <b>계정이 존재하는지는 알려주지 않는다</b>.
 * 가입에서는 중복 여부를 말해야만 하고(그러지 않으면 가입 자체가 불가능하다),
 * 로그인에서는 말하지 않는다 — 같은 정보라도 어디서 새느냐가 다르다.
 */
@RestControllerAdvice(assignableTypes = AccountController.class)
class AccountExceptionHandler {

    /** {@code @Valid} 실패. 칸 이름과 메시지를 그대로 돌려준다. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e) {
        var fields = new LinkedHashMap<String, String>();
        for (var error : e.getBindingResult().getFieldErrors()) {
            // 비밀번호 확인 불일치는 isPasswordConfirmed 라는 이름으로 온다.
            // 화면이 붙일 칸은 passwordConfirm 이다.
            String field = "passwordConfirmed".equals(error.getField())
                    ? "passwordConfirm"
                    : error.getField();
            fields.putIfAbsent(field, error.getDefaultMessage());
        }
        return ResponseEntity.badRequest()
                .body(Map.of("message", "입력을 확인해 주세요", "fields", fields));
    }

    @ExceptionHandler(AccountService.DuplicateException.class)
    ResponseEntity<Map<String, Object>> duplicate(AccountService.DuplicateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of(
                        "message", e.getMessage(),
                        "fields", Map.of(e.field(), e.getMessage())));
    }

    @ExceptionHandler(AccountController.BadCredentials.class)
    ResponseEntity<Map<String, String>> badCredentials(AccountController.BadCredentials e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("message", e.getMessage()));
    }
}
