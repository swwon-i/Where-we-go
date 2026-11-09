package com.wherewego.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.InetSocketAddress;
import java.net.Socket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * 탈퇴.
 *
 * <p>계정은 지우되 <b>여럿이 함께 만든 것은 남긴다</b>. 그래서 확인할 것이 둘이다 —
 * 다시 들어올 수 없게 되었는가, 그리고 남은 사람들의 방과 후보가 그대로인가.
 *
 * <p>방에서 빠지는 규칙은 "방 나가기"와 같은 코드를 쓴다. 여기서는 그 규칙이 탈퇴에도
 * 걸리는지만 본다 — 규칙 자체는 {@code LeaveAndDeleteTest} 가 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@EnabledIf("databaseIsUp")
class WithdrawalTest {

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

    private final JsonMapper json = JsonMapper.builder().build();

    private MockHttpSession leaver;
    private MockHttpSession stayer;

    @BeforeEach
    void signUpTwoPeople() throws Exception {
        leaver = signUp("떠나는사람", "bye_leaver");
        stayer = signUp("남는사람", "bye_stayer");
    }

    private MockHttpSession signUp(String nickname, String loginId) throws Exception {
        var session = new MockHttpSession();
        mvc.perform(post("/api/v1/auth/signup")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nickname":"%s","loginId":"%s",
                                 "password":"%s","passwordConfirm":"%s"}
                                """.formatted(nickname, loginId, PW, PW)))
                .andExpect(status().isCreated());
        return session;
    }

    private MockHttpServletRequestBuilder json(
            MockHttpServletRequestBuilder builder, MockHttpSession session, String body) {
        return builder.session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private String createRoom(MockHttpSession session) throws Exception {
        var response = mvc.perform(
                        json(post("/api/v1/rooms"), session, "{\"title\":\"저녁 약속\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("roomId").asString();
    }

    private void join(MockHttpSession session, String roomId) throws Exception {
        mvc.perform(post("/api/v1/rooms/" + roomId + "/members").session(session).with(csrf()))
                .andExpect(status().isOk());
    }

    private void withdraw(MockHttpSession session) throws Exception {
        mvc.perform(delete("/api/v1/auth/me").session(session).with(csrf()))
                .andExpect(status().isNoContent());
    }

    private MockHttpServletRequestBuilder login(String loginId, String password) {
        return post("/api/v1/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginId\":\"%s\",\"password\":\"%s\"}".formatted(loginId, password));
    }

    // ── 계정 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("탈퇴하면 같은 아이디와 비밀번호로 다시 들어올 수 없다")
    void cannotLogBackIn() throws Exception {
        withdraw(leaver);

        mvc.perform(login("bye_leaver", PW)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("탈퇴한 계정은 빈 비밀번호로도 열리지 않는다 — 해시 자리에 맞는 값이 없다")
    void emptyPasswordDoesNotWork() throws Exception {
        long id = userId("bye_leaver");
        withdraw(leaver);

        mvc.perform(login("deleted_" + id, "")).andExpect(status().isUnauthorized());
        mvc.perform(login("deleted_" + id, "deleted")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("쓰던 아이디와 닉네임은 풀린다 — 다른 사람이 가져갈 수 있다")
    void identifiersAreFreed() throws Exception {
        withdraw(leaver);

        var reused = new MockHttpSession();
        mvc.perform(post("/api/v1/auth/signup")
                        .session(reused)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nickname":"떠나는사람","loginId":"bye_leaver",
                                 "password":"%s","passwordConfirm":"%s"}
                                """.formatted(PW, PW)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("탈퇴하면 세션도 끊긴다")
    void sessionEnds() throws Exception {
        withdraw(leaver);

        mvc.perform(get("/api/v1/auth/me").session(leaver)).andExpect(status().isUnauthorized());
    }

    // ── 방 ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("탈퇴해도 남은 사람이 있는 방은 사라지지 않고 방장이 넘어간다")
    void ownedRoomSurvivesWithANewOwner() throws Exception {
        var roomId = createRoom(leaver);
        join(stayer, roomId);

        withdraw(leaver);

        mvc.perform(get("/api/v1/rooms/" + roomId).session(stayer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerNickname").value("남는사람"))
                .andExpect(jsonPath("$.iAmOwner").value(true))
                .andExpect(jsonPath("$.members.length()").value(1));
    }

    @Test
    @DisplayName("혼자 쓰던 방은 탈퇴와 함께 지워진다")
    void soloRoomGoes() throws Exception {
        var roomId = createRoom(leaver);

        withdraw(leaver);

        Integer left = jdbc.queryForObject(
                "SELECT count(*) FROM room WHERE id = ?::uuid", Integer.class, roomId);
        assertThat(left).isZero();
    }

    @Test
    @DisplayName("탈퇴한 사람이 담은 후보는 남고 '탈퇴한 사용자'로 보인다")
    void bookmarksRemainUnderAnonymousName() throws Exception {
        var roomId = createRoom(stayer);
        join(leaver, roomId);
        mvc.perform(json(post("/api/v1/rooms/" + roomId + "/bookmarks"), leaver,
                        "{\"name\":\"떠나는사람의 후보\",\"lng\":127.01,\"lat\":37.52}"))
                .andExpect(status().isCreated());

        withdraw(leaver);

        mvc.perform(get("/api/v1/rooms/" + roomId).session(stayer))
                .andExpect(jsonPath("$.bookmarks.length()").value(1))
                .andExpect(jsonPath("$.bookmarks[0].addedByNickname").value("탈퇴한 사용자"));
    }

    @Test
    @DisplayName("남의 방에서는 참가자 목록에서만 빠진다")
    void leavesOtherPeoplesRooms() throws Exception {
        var roomId = createRoom(stayer);
        join(leaver, roomId);

        withdraw(leaver);

        mvc.perform(get("/api/v1/rooms/" + roomId).session(stayer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members.length()").value(1))
                .andExpect(jsonPath("$.ownerNickname").value("남는사람"));
    }

    @Test
    @DisplayName("로그인하지 않았으면 탈퇴할 것도 없다")
    void anonymousCannotWithdraw() throws Exception {
        mvc.perform(delete("/api/v1/auth/me").with(csrf())).andExpect(status().isUnauthorized());
    }

    private long userId(String loginId) {
        return jdbc.queryForObject(
                "SELECT id FROM app_user WHERE login_id = ?", Long.class, loginId);
    }
}
