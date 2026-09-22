package com.wherewego.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** 요청 횟수 제한. 시계를 바꿔 끼워 창이 지나는 것을 기다리지 않고 본다. */
class RateLimitFilterTest {

    /** 손으로 넘기는 시계. */
    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-09-23T00:00:00Z");

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration d) { now = now.plus(d); }
    }

    private final MutableClock clock = new MutableClock();

    private int call(RateLimitFilter filter, String method, String path, String ip) throws Exception {
        var request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(ip);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }

    @Test
    @DisplayName("가입은 IP 당 한 시간에 5번 — 6번째는 429")
    void signupLimit() throws Exception {
        var filter = new RateLimitFilter(true, clock);
        for (int i = 0; i < 5; i++) {
            assertThat(call(filter, "POST", "/api/v1/auth/signup", "1.1.1.1")).isEqualTo(200);
        }
        var request = new MockHttpServletRequest("POST", "/api/v1/auth/signup");
        request.setRemoteAddr("1.1.1.1");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("3600");
        assertThat(response.getContentAsString()).contains("\"message\"");
    }

    @Test
    @DisplayName("다른 IP 는 따로 센다")
    void perIp() throws Exception {
        var filter = new RateLimitFilter(true, clock);
        for (int i = 0; i < 5; i++) call(filter, "POST", "/api/v1/auth/signup", "1.1.1.1");
        assertThat(call(filter, "POST", "/api/v1/auth/signup", "2.2.2.2")).isEqualTo(200);
    }

    @Test
    @DisplayName("창이 지나면 다시 열린다")
    void windowResets() throws Exception {
        var filter = new RateLimitFilter(true, clock);
        for (int i = 0; i < 6; i++) call(filter, "POST", "/api/v1/auth/signup", "1.1.1.1");
        clock.advance(Duration.ofHours(1));
        assertThat(call(filter, "POST", "/api/v1/auth/signup", "1.1.1.1")).isEqualTo(200);
    }

    @Test
    @DisplayName("규칙이 없는 요청은 세지 않는다 — 방 조회·장소 검색은 얼마든지")
    void unlistedRequestsPass() throws Exception {
        var filter = new RateLimitFilter(true, clock);
        for (int i = 0; i < 100; i++) {
            assertThat(call(filter, "GET", "/api/v1/places/nearby", "1.1.1.1")).isEqualTo(200);
            assertThat(call(filter, "GET", "/api/v1/rooms", "1.1.1.1")).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("행렬은 방 경로 모양으로 맞춘다 — 방마다가 아니라 IP 마다 60번")
    void matrixPattern() throws Exception {
        var filter = new RateLimitFilter(true, clock);
        for (int i = 0; i < 60; i++) {
            call(filter, "POST", "/api/v1/rooms/room-" + (i % 3) + "/matrix", "1.1.1.1");
        }
        assertThat(call(filter, "POST", "/api/v1/rooms/another/matrix", "1.1.1.1")).isEqualTo(429);
        assertThat(call(filter, "POST", "/api/v1/rooms", "1.1.1.1")).isEqualTo(200);   // 방 만들기는 따로
    }

    @Test
    @DisplayName("끄면 아무것도 막지 않는다 — 로컬과 테스트의 기본값")
    void disabled() throws Exception {
        var filter = new RateLimitFilter(false, clock);
        for (int i = 0; i < 20; i++) {
            assertThat(call(filter, "POST", "/api/v1/auth/signup", "1.1.1.1")).isEqualTo(200);
        }
    }
}
