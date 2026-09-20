package com.wherewego.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.InetSocketAddress;
import java.net.Socket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 가입 → 로그인 → 로그아웃 전체 흐름. DB 가 없으면 건너뛴다.
 *
 * <p>{@code @Transactional} 이라 여기서 만든 계정은 테스트가 끝나면 사라진다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@EnabledIf("databaseIsUp")
class AccountFlowTest {

    private static final String PW = "s3cret-pw";

    static boolean databaseIsUp() {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 5432), 300);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private static String body(String nickname, String loginId, String pw, String confirm) {
        return """
                {"nickname":"%s","loginId":"%s","password":"%s","passwordConfirm":"%s"}
                """.formatted(nickname, loginId, pw, confirm);
    }

    private static String loginBody(String loginId, String pw) {
        return """
                {"loginId":"%s","password":"%s"}
                """.formatted(loginId, pw);
    }

    private void signup(String nickname, String loginId) throws Exception {
        mvc.perform(post("/api/v1/auth/signup")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(nickname, loginId, PW, PW)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("가입하면 바로 로그인된 상태다 — 방금 정한 비밀번호를 또 치게 하지 않는다")
    void signupLogsIn() throws Exception {
        var session = new MockHttpSession();
        mvc.perform(post("/api/v1/auth/signup")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("가입자", "signup_user", PW, PW)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nickname").value("가입자"));

        mvc.perform(get("/api/v1/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginId").value("signup_user"));
    }

    @Test
    @DisplayName("비밀번호는 해시로만 저장된다 — 평문이 DB 에 남으면 안 된다")
    void passwordIsHashed() throws Exception {
        signup("해시확인", "hash_check");
        String stored = jdbc.queryForObject(
                "SELECT password_hash FROM app_user WHERE login_id = ?", String.class, "hash_check");

        assertThat(stored).doesNotContain(PW);
        assertThat(stored).startsWith("{bcrypt}$2");
    }

    @Test
    @DisplayName("응답 어디에도 해시가 나가지 않는다")
    void hashNeverLeaves() throws Exception {
        String response = mvc.perform(post("/api/v1/auth/signup")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("유출확인", "leak_check", PW, PW)))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).doesNotContain("bcrypt").doesNotContain("passwordHash");
    }

    @Test
    @DisplayName("같은 아이디는 409, 어느 칸이 문제인지 알려준다")
    void duplicateLoginId() throws Exception {
        signup("첫번째", "same_id");
        mvc.perform(post("/api/v1/auth/signup")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("두번째", "same_id", PW, PW)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.fields.loginId").exists());
    }

    @Test
    @DisplayName("대소문자만 다른 닉네임도 중복이다 — 방 화면에서 서로를 구분 못 한다")
    void duplicateNicknameIgnoringCase() throws Exception {
        signup("Hong", "nick_a");
        mvc.perform(post("/api/v1/auth/signup")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("hong", "nick_b", PW, PW)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.fields.nickname").exists());
    }

    @Test
    @DisplayName("비밀번호 확인이 다르면 400 이고 계정이 만들어지지 않는다")
    void mismatchedConfirmIsRejected() throws Exception {
        mvc.perform(post("/api/v1/auth/signup")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("불일치", "mismatch_u", PW, "other-pw1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.passwordConfirm").exists());

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app_user WHERE login_id = ?",
                        Integer.class,
                        "mismatch_u"))
                .isZero();
    }

    @Test
    @DisplayName("로그인은 아이디의 대소문자를 가리지 않는다")
    void loginIsCaseInsensitive() throws Exception {
        signup("대소문자", "case_user");
        mvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("CASE_USER", PW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("대소문자"));
    }

    @Test
    @DisplayName("틀린 비밀번호는 401 이고, 아이디가 있는지는 알려주지 않는다")
    void wrongPasswordRevealsNothing() throws Exception {
        signup("비번틀림", "wrong_pw_u");

        String existing = mvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("wrong_pw_u", "not-the-pw")))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        String missing = mvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("no_such_user", "not-the-pw")))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        // 있는 계정과 없는 계정의 응답이 같아야 한다. 다르면 아이디 존재 여부를 캐낼 수 있다.
        assertThat(existing).isEqualTo(missing);
    }

    @Test
    @DisplayName("로그아웃하면 세션이 끊긴다")
    void logoutEndsSession() throws Exception {
        var session = new MockHttpSession();
        mvc.perform(post("/api/v1/auth/signup")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("로그아웃", "logout_u", PW, PW)))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/auth/logout").session(session).with(csrf()))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/auth/me").session(session))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("로그인하지 않으면 401 — 로그인 폼으로 넘기지 않는다")
    void unauthenticatedGets401() throws Exception {
        mvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("장소 검색은 로그인 없이 열려 있다 — 방을 만들기 전에 둘러볼 수 있어야 한다")
    void placesStayPublic() throws Exception {
        mvc.perform(get("/api/v1/places/nearby")
                        .param("lng", "127.0276")
                        .param("lat", "37.4979")
                        .param("radius", "200"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("CSRF 토큰이 없으면 거부한다 — 세션 쿠키만으로 남의 API 를 부르지 못하게")
    void csrfIsRequired() throws Exception {
        mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("토큰없음", "no_csrf_u", PW, PW)))
                .andExpect(status().isForbidden());
    }
}
