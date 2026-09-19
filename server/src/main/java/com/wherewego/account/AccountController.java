package com.wherewego.account;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 가입 · 로그인 · 로그아웃.
 *
 * <pre>
 *   POST /api/v1/auth/signup   {nickname, loginId, password, passwordConfirm}
 *   POST /api/v1/auth/login    {loginId, password}
 *   POST /api/v1/auth/logout
 *   GET  /api/v1/auth/me
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AccountController {

    private final AccountService accounts;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository contextRepository;
    private final SessionAuthenticationStrategy sessionStrategy;

    public AccountController(
            AccountService accounts,
            AuthenticationManager authenticationManager,
            SecurityContextRepository contextRepository,
            SessionAuthenticationStrategy sessionStrategy) {
        this.accounts = accounts;
        this.authenticationManager = authenticationManager;
        this.contextRepository = contextRepository;
        this.sessionStrategy = sessionStrategy;
    }

    /** 밖으로 나가는 계정 정보. 해시는 물론이고 로그인 아이디도 남에게는 보이지 않는다. */
    public record Me(long userId, String loginId, String nickname) {}

    public record LoginRequest(String loginId, String password) {}

    @PostMapping("/signup")
    public ResponseEntity<Me> signup(
            @Valid @RequestBody SignupRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        long id = accounts.signup(request);
        var user = accounts.byId(id);

        // 가입 직후 바로 로그인시킨다. 방금 정한 비밀번호를 한 번 더 치게 할 이유가 없다.
        authenticate(request.normalizedLoginId(), request.password(), httpRequest, httpResponse);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new Me(user.id(), user.loginId(), user.nickname()));
    }

    @PostMapping("/login")
    public Me login(
            @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        authenticate(request.loginId(), request.password(), httpRequest, httpResponse);
        var user = accounts.byId(currentUserId());
        return new Me(user.id(), user.loginId(), user.nickname());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) {
            // 세션을 무효화한다. 컨텍스트만 비우면 세션은 살아 있다.
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }

    /**
     * CSRF 토큰을 받아 간다.
     *
     * <p>토큰은 <b>누군가 요구해야 만들어진다</b>. 아무도 읽지 않으면 쿠키도 내려가지 않고,
     * 그러면 프론트는 헤더에 넣을 값이 없어 모든 POST 가 403 이 된다. 첫 화면에서 이것을
     * 한 번 부르는 것이 시작점이다.
     *
     * <p>{@code CsrfToken} 을 인자로 받는 것 자체가 토큰을 만들게 하는 동작이다.
     */
    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    @GetMapping("/me")
    public Me me() {
        var user = accounts.byId(currentUserId());
        return new Me(user.id(), user.loginId(), user.nickname());
    }

    /**
     * 자격 증명을 확인하고 세션에 담는다.
     *
     * <p>비밀번호 비교는 {@code AuthenticationManager} 가 한다. 실패하면 아이디가 없는 것인지
     * 비밀번호가 틀린 것인지 <b>구분해 알려주지 않는다</b> — 구분해 주면 어떤 아이디가 존재하는지
     * 확인하는 통로가 된다.
     */
    private void authenticate(
            String loginId,
            String password,
            HttpServletRequest request,
            HttpServletResponse response) {

        org.springframework.security.core.Authentication auth;
        try {
            auth = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(loginId, password));
        } catch (AuthenticationException e) {
            throw new BadCredentials();
        }

        // 세션 id 를 새로 발급한 뒤에 컨텍스트를 저장한다. 순서가 반대면 옛 세션에 저장된다.
        sessionStrategy.onAuthentication(auth, request, response);

        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
    }

    private long currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return accounts.byLoginId(auth.getName()).id();
    }

    /** 아이디나 비밀번호가 틀렸다. 어느 쪽인지는 말하지 않는다. */
    static class BadCredentials extends RuntimeException {
        BadCredentials() {
            super("아이디나 비밀번호가 올바르지 않습니다");
        }
    }
}
