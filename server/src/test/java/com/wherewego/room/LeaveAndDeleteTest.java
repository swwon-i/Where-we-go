package com.wherewego.room;

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
import org.junit.jupiter.api.Nested;
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
 * 방 나가기 · 방 삭제 · 탈퇴.
 *
 * <p>셋이 규칙 하나를 같이 쓴다 — <b>내가 방에서 빠질 때, 남은 사람이 있으면 가장 먼저
 * 들어온 사람에게 방장을 넘기고, 내가 마지막이면 방이 지워진다.</b> 사람이 셋 필요하다:
 * 넘겨받는 순서가 들어온 차례대로인지는 둘로는 드러나지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@EnabledIf("databaseIsUp")
class LeaveAndDeleteTest {

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

    private MockHttpSession owner;
    private MockHttpSession first;
    private MockHttpSession second;

    @BeforeEach
    void signUpThreePeople() throws Exception {
        owner = signUp("나간방주인", "leave_owner");
        first = signUp("먼저온사람", "leave_first");
        second = signUp("나중에온사람", "leave_second");
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
                .andReturn()
                .getResponse()
                .getContentAsString();
        return json.readTree(response).get("roomId").asString();
    }

    private void join(MockHttpSession session, String roomId) throws Exception {
        mvc.perform(post("/api/v1/rooms/" + roomId + "/members").session(session).with(csrf()))
                .andExpect(status().isOk());
    }

    /** 셋이 들어 있는 방. 들어온 차례는 주인 → first → second 다. */
    private String roomOfThree() throws Exception {
        var roomId = createRoom(owner);
        join(first, roomId);
        join(second, roomId);
        return roomId;
    }

    private long bookmark(MockHttpSession session, String roomId, String name) throws Exception {
        var body = "{\"name\":\"%s\",\"lng\":127.01,\"lat\":37.52}".formatted(name);
        var response = mvc.perform(json(post("/api/v1/rooms/" + roomId + "/bookmarks"), session, body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("bookmarkId").asLong();
    }

    private int roomCount(String roomId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM room WHERE id = ?::uuid", Integer.class, roomId);
    }

    // ── 나가기 ──────────────────────────────────────────────────────────────

    @Nested
    class Leaving {

        @Test
        @DisplayName("나가면 방에서 빠지고, 남은 사람들의 방은 그대로다")
        void memberLeaves() throws Exception {
            var roomId = roomOfThree();

            mvc.perform(delete("/api/v1/rooms/" + roomId + "/members/me").session(second).with(csrf()))
                    .andExpect(status().isNoContent());

            // 나간 사람은 더 이상 볼 수 없다
            mvc.perform(get("/api/v1/rooms/" + roomId).session(second))
                    .andExpect(status().isForbidden());
            // 남은 사람에게는 둘만 보인다
            mvc.perform(get("/api/v1/rooms/" + roomId).session(owner))
                    .andExpect(jsonPath("$.members.length()").value(2));
        }

        @Test
        @DisplayName("방장이 나가면 가장 먼저 들어온 사람이 방장이 된다 — 방은 사라지지 않는다")
        void ownerHandsOver() throws Exception {
            var roomId = roomOfThree();

            mvc.perform(delete("/api/v1/rooms/" + roomId + "/members/me").session(owner).with(csrf()))
                    .andExpect(status().isNoContent());

            assertThat(roomCount(roomId)).isEqualTo(1);
            mvc.perform(get("/api/v1/rooms/" + roomId).session(first))
                    .andExpect(jsonPath("$.ownerNickname").value("먼저온사람"))
                    .andExpect(jsonPath("$.iAmOwner").value(true))
                    .andExpect(jsonPath("$.members.length()").value(2));
            // 나중에 온 사람에게 넘어가지 않는다 — 아무에게나 가는 것이 아니다
            mvc.perform(get("/api/v1/rooms/" + roomId).session(second))
                    .andExpect(jsonPath("$.iAmOwner").value(false));
        }

        @Test
        @DisplayName("새 방장은 코드를 다시 뽑을 수 있다 — 넘어간 것은 이름이 아니라 권한이다")
        void newOwnerHasOwnerPowers() throws Exception {
            var roomId = roomOfThree();
            mvc.perform(delete("/api/v1/rooms/" + roomId + "/members/me").session(owner).with(csrf()))
                    .andExpect(status().isNoContent());

            mvc.perform(post("/api/v1/rooms/" + roomId + "/code").session(first).with(csrf()))
                    .andExpect(status().isOk());
            mvc.perform(post("/api/v1/rooms/" + roomId + "/code").session(second).with(csrf()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("마지막 한 명이 나가면 방도 지워진다 — 아무도 없는 방을 남길 이유가 없다")
        void lastOneOutDeletesTheRoom() throws Exception {
            var roomId = createRoom(owner);

            mvc.perform(delete("/api/v1/rooms/" + roomId + "/members/me").session(owner).with(csrf()))
                    .andExpect(status().isNoContent());

            assertThat(roomCount(roomId)).isZero();
        }

        @Test
        @DisplayName("나간 사람이 담은 후보는 남는다 — 모임의 공동 작업이지 내 소유물이 아니다")
        void bookmarksSurvive() throws Exception {
            var roomId = roomOfThree();
            bookmark(second, roomId, "나중에온사람의 후보");

            mvc.perform(delete("/api/v1/rooms/" + roomId + "/members/me").session(second).with(csrf()))
                    .andExpect(status().isNoContent());

            mvc.perform(get("/api/v1/rooms/" + roomId).session(owner))
                    .andExpect(jsonPath("$.bookmarks.length()").value(1))
                    .andExpect(jsonPath("$.bookmarks[0].addedByNickname").value("나중에온사람"));
        }

        @Test
        @DisplayName("참가자가 아니면 나갈 것도 없다")
        void nonMemberCannotLeave() throws Exception {
            var roomId = createRoom(owner);
            mvc.perform(delete("/api/v1/rooms/" + roomId + "/members/me").session(first).with(csrf()))
                    .andExpect(status().isForbidden());
        }
    }

    // ── 방 삭제 ─────────────────────────────────────────────────────────────

    @Nested
    class Deleting {

        @Test
        @DisplayName("방장이 지우면 모두가 방을 잃는다")
        void ownerDeletes() throws Exception {
            var roomId = roomOfThree();

            mvc.perform(delete("/api/v1/rooms/" + roomId).session(owner).with(csrf()))
                    .andExpect(status().isNoContent());

            assertThat(roomCount(roomId)).isZero();
            mvc.perform(get("/api/v1/rooms/" + roomId).session(first))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("방장이 아니면 지우지 못한다 — 나가기와 삭제는 다른 뜻이다")
        void memberCannotDelete() throws Exception {
            var roomId = roomOfThree();

            mvc.perform(delete("/api/v1/rooms/" + roomId).session(first).with(csrf()))
                    .andExpect(status().isForbidden());

            assertThat(roomCount(roomId)).isEqualTo(1);
        }

        @Test
        @DisplayName("지운 방의 후보도 함께 사라진다")
        void bookmarksGoWithTheRoom() throws Exception {
            var roomId = roomOfThree();
            bookmark(first, roomId, "사라질 후보");

            mvc.perform(delete("/api/v1/rooms/" + roomId).session(owner).with(csrf()))
                    .andExpect(status().isNoContent());

            Integer left = jdbc.queryForObject(
                    "SELECT count(*) FROM bookmark WHERE room_id = ?::uuid", Integer.class, roomId);
            assertThat(left).isZero();
        }
    }

    // ── 후보 빼기 권한 ──────────────────────────────────────────────────────

    @Nested
    class RemovingBookmarks {

        @Test
        @DisplayName("방장은 남이 담은 후보도 뺀다 — 담은 사람이 떠나면 아무도 못 빼게 된다")
        void ownerCanRemoveOthers() throws Exception {
            var roomId = roomOfThree();
            long id = bookmark(first, roomId, "먼저온사람의 후보");

            mvc.perform(delete("/api/v1/rooms/" + roomId + "/bookmarks/" + id)
                            .session(owner).with(csrf()))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("방장도 담은 사람도 아니면 빼지 못한다")
        void strangerCannotRemove() throws Exception {
            var roomId = roomOfThree();
            long id = bookmark(first, roomId, "먼저온사람의 후보");

            mvc.perform(delete("/api/v1/rooms/" + roomId + "/bookmarks/" + id)
                            .session(second).with(csrf()))
                    .andExpect(status().isForbidden());
        }
    }
}
