package com.wherewego.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.net.InetSocketAddress;
import java.net.Socket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * 프론트가 실제로 겪는 CSRF 절차.
 *
 * <h2>왜 따로 있나</h2>
 *
 * 다른 테스트는 {@code with(csrf())} 로 토큰을 밀어 넣는다. 그 방법은 <b>토큰이 어떻게
 * 발급되는지를 건너뛴다</b> — 토큰 쿠키가 한 번도 안 내려가고 있어도 통과한다. 실제로
 * 그랬다. 브라우저에서는 헤더에 넣을 값이 없어 모든 POST 가 403 이 될 상태였는데 테스트는
 * 전부 초록색이었다. 그래서 여기서는 토큰을 받아오는 것부터 직접 한다.
 *
 * <h2>왜 컨텍스트를 새로 띄우나</h2>
 *
 * {@code with(csrf())} 는 요청에만 작용하지 않는다. 공유 중인 {@code CsrfFilter} 의
 * 토큰 저장소를 <b>테스트용 구현으로 바꿔 끼운다</b>. 스프링은 컨텍스트를 테스트 클래스 간에
 * 재사용하므로, 그 뒤에 도는 이 테스트는 우리 설정이 아니라 테스트용 저장소를 보게 된다.
 *
 * <p>실제로 이 클래스만 돌리면 통과하고 전체를 돌리면 실패했다. 같은 코드가 실행 순서에 따라
 * 다른 결과를 냈다는 뜻이고, 원인을 모른 채 보면 설정이 틀린 것처럼 보인다.
 * {@link DirtiesContext} 로 이 클래스 앞에서 컨텍스트를 새로 띄워 떼어 놓는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
@EnabledIf("databaseIsUp")
class CsrfHandshakeTest {

    static boolean databaseIsUp() {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 5432), 300);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Autowired MockMvc mvc;

    private final JsonMapper json = JsonMapper.builder().build();

    /** 토큰과 쿠키를 함께 들고 다닌다. 브라우저가 하는 일과 같다. */
    private record Handshake(String headerName, String token, Cookie cookie) {}

    private Handshake handshake(MockHttpSession session) throws Exception {
        var response = mvc.perform(get("/api/v1/auth/csrf").session(session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();

        var body = json.readTree(response.getContentAsString());
        return new Handshake(
                body.get("headerName").asString(),
                body.get("token").asString(),
                response.getCookie("XSRF-TOKEN"));
    }

    private static String signupBody(String nickname, String loginId) {
        return """
                {"nickname":"%s","loginId":"%s",
                 "password":"s3cret-pw","passwordConfirm":"s3cret-pw"}
                """.formatted(nickname, loginId);
    }

    @Test
    @DisplayName("토큰을 요청하면 XSRF-TOKEN 쿠키가 내려온다 — 없으면 프론트가 시작조차 못 한다")
    void tokenEndpointIssuesCookie() throws Exception {
        var shake = handshake(new MockHttpSession());

        assertThat(shake.token()).isNotBlank();
        // 쿠키가 XSRF-TOKEN 이면 헤더는 X-XSRF-TOKEN 이어야 짝이 맞는다.
        assertThat(shake.headerName()).isEqualTo("X-XSRF-TOKEN");
        assertThat(shake.cookie()).as("XSRF-TOKEN 쿠키가 내려오지 않았다").isNotNull();
        // 프론트의 자바스크립트가 읽어 헤더에 실어야 하므로 HttpOnly 가 아니어야 한다.
        assertThat(shake.cookie().isHttpOnly()).isFalse();
    }

    @Test
    @DisplayName("받아온 토큰으로 가입이 된다 — 절차 전체가 실제로 맞물린다")
    void tokenFromEndpointWorksForPost() throws Exception {
        var session = new MockHttpSession();
        var shake = handshake(session);

        mvc.perform(post("/api/v1/auth/signup")
                        .session(session)
                        .cookie(shake.cookie())
                        .header(shake.headerName(), shake.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody("절차확인", "csrf_flow")))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("헤더 없이 쿠키만 보내면 거부한다 — 남의 사이트는 우리 쿠키를 읽지 못한다")
    void cookieAloneIsNotEnough() throws Exception {
        var session = new MockHttpSession();
        var shake = handshake(session);

        mvc.perform(post("/api/v1/auth/signup")
                        .session(session)
                        .cookie(shake.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody("쿠키만", "cookie_only")))
                .andExpect(status().isForbidden());
    }
}
