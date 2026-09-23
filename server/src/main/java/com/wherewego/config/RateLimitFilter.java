package com.wherewego.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 접속자(IP)마다 요청 횟수를 제한한다. 공개 서버에서만 켠다 ({@code wwg.rate-limit.enabled}).
 *
 * <p>누구나 가입할 수 있는 서비스라 막아 둘 자리가 넷 있다.
 *
 * <ul>
 *   <li><b>가입</b> — 계정을 찍어 내면 닉네임·아이디가 고갈되고 DB 가 차오른다
 *   <li><b>로그인</b> — 비밀번호 대입. BCrypt 가 느리긴 해도 무한히 두드리게 둘 이유가 없다
 *   <li><b>방 만들기</b> — 방 하나에 초대 코드 하나. 코드 공간을 채우는 공격
 *   <li><b>행렬 계산</b> — 한 번에 다익스트라가 사람 수만큼 돈다. 방마다 한 번에 하나로
 *       막혀 있지만(MatrixService) 방을 여러 개 두면 우회된다
 *   <li><b>단건 경로·장소 검색</b> — 로그인 없이 열려 있다. 경로는 한 번에 다익스트라가 돈다
 *   <li><b>초대 코드로 참가</b> — 코드 찍어 보기
 * </ul>
 *
 * <h2>왜 메모리에 두나</h2>
 *
 * 서버가 한 대다. Redis 를 두면 제한은 정확해지지만 움직이는 부품이 하나 는다. 재시작하면
 * 카운터가 비는데, 그 사이 새는 것은 몇 번의 요청이다 — 막으려는 것은 초당 수백 번이다.
 * 세션도 같은 이유로 메모리에 있다.
 *
 * <p>고정 창(fixed window)이다. 창 경계에서 두 배까지 통과할 수 있지만 위 목적에는 충분하다.
 *
 * <h2>IP</h2>
 *
 * {@code request.getRemoteAddr()} 를 쓴다. 운영에서는 Caddy 뒤라 그대로면 전부 Caddy 의 주소가
 * 되므로 {@code server.forward-headers-strategy=native} 로 {@code X-Forwarded-For} 를 믿게 한다
 * (docker-compose.prod.yml). 서버 포트가 밖에 열려 있지 않아 이 헤더는 Caddy 만 붙일 수 있다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    /** 한 규칙. {@code path} 는 요청 경로 전체와 맞춘다. */
    record Rule(String name, String method, Pattern path, int limit, Duration window) {}

    static final List<Rule> RULES = List.of(
            new Rule("signup", "POST", Pattern.compile("/api/v1/auth/signup"), 5, Duration.ofHours(1)),
            new Rule("login", "POST", Pattern.compile("/api/v1/auth/login"), 20, Duration.ofMinutes(10)),
            // 초대 코드 찍어 보기. 8자 Crockford Base32 라 확률은 낮지만 두드리게 둘 이유가 없다
            new Rule("join", "POST", Pattern.compile("/api/v1/rooms/join"), 30, Duration.ofMinutes(10)),
            new Rule("room", "POST", Pattern.compile("/api/v1/rooms"), 30, Duration.ofHours(1)),
            new Rule("matrix", "POST", Pattern.compile("/api/v1/rooms/[^/]+/matrix"), 60, Duration.ofMinutes(10)),
            // 로그인 없이 열려 있고 한 번에 다익스트라가 도는 곳(잰 값 35~75ms). 제한이 없으면
            // 한 사람이 서버를 다 쓴다
            new Rule("route", "GET", Pattern.compile("/api/v1/routes"), 60, Duration.ofMinutes(10)),
            // DB 조회. 반경·건수는 이미 잘려 있지만(PlaceRepository) 횟수는 제한이 없었다
            new Rule("places", "GET", Pattern.compile("/api/v1/places/.*"), 300, Duration.ofMinutes(10)));

    /** 카운터가 이만큼 쌓이면 끝난 창을 치운다. 접속자가 많아도 메모리가 끝없이 늘지 않게. */
    private static final int SWEEP_AT = 10_000;

    private record Window(long startMillis, int count) {}

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final boolean enabled;
    private final Clock clock;

    @Autowired
    public RateLimitFilter(@Value("${wwg.rate-limit.enabled:false}") boolean enabled) {
        this(enabled, Clock.systemUTC());
    }

    RateLimitFilter(boolean enabled, Clock clock) {
        this.enabled = enabled;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled || ruleFor(request) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var rule = ruleFor(request);
        long now = clock.millis();
        long windowMs = rule.window().toMillis();

        var w = windows.compute(rule.name() + '|' + request.getRemoteAddr(), (k, old) ->
                old == null || now - old.startMillis() >= windowMs
                        ? new Window(now, 1)
                        : new Window(old.startMillis(), old.count() + 1));

        if (windows.size() > SWEEP_AT) sweep(now);

        if (w.count() > rule.limit()) {
            long retryAfter = Math.max(1, (w.startMillis() + windowMs - now) / 1000);
            response.setStatus(429);
            response.setHeader("Retry-After", Long.toString(retryAfter));
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(
                    "{\"message\":\"요청이 너무 많습니다. " + minutes(retryAfter) + " 뒤에 다시 시도해 주세요\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private static Rule ruleFor(HttpServletRequest request) {
        for (var rule : RULES) {
            if (rule.method().equals(request.getMethod()) && rule.path().matcher(request.getRequestURI()).matches()) {
                return rule;
            }
        }
        return null;
    }

    private void sweep(long now) {
        long longest = RULES.stream().mapToLong(r -> r.window().toMillis()).max().orElse(0);
        windows.values().removeIf(w -> now - w.startMillis() >= longest);
    }

    private static String minutes(long seconds) {
        return seconds < 60 ? seconds + "초" : (seconds + 59) / 60 + "분";
    }
}
