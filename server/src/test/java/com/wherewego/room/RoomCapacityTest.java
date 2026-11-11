package com.wherewego.room;

import static com.wherewego.room.RoomService.MAX_BOOKMARKS;
import static com.wherewego.room.RoomService.MAX_MEMBERS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * 방 상한 — 인원 {@link RoomService#MAX_MEMBERS} 명, 후보 {@link RoomService#MAX_BOOKMARKS} 곳.
 *
 * <p>행렬은 사람마다 탐색을 한 번씩 순서대로 돌므로 인원이 곧 요청 하나의 무게다. 상한이 없으면
 * 큰 방 하나가 서버의 vCPU 하나를 수십 초 붙잡는다(docs/perf-server.md). 후보는 칸 수(인원 × 후보)와
 * 스냅 질의 수를 정한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@EnabledIf("databaseIsUp")
class RoomCapacityTest {

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

    private MockHttpSession signUp(String loginId) throws Exception {
        var session = new MockHttpSession();
        mvc.perform(post("/api/v1/auth/signup")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nickname":"%s","loginId":"%s",
                                 "password":"%s","passwordConfirm":"%s"}
                                """.formatted(loginId, loginId, PW, PW)))
                .andExpect(status().isCreated());
        return session;
    }

    private MockHttpServletRequestBuilder json(
            MockHttpServletRequestBuilder builder, MockHttpSession session, String body) {
        return builder.session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private MockHttpServletRequestBuilder joinRequest(MockHttpSession session, String roomId) {
        return post("/api/v1/rooms/" + roomId + "/members").session(session).with(csrf());
    }

    /** 주인 포함 {@code size} 명이 든 방. */
    private record Room(String id, MockHttpSession owner, List<MockHttpSession> members) {}

    private Room roomOf(int size, String prefix) throws Exception {
        var owner = signUp(prefix + "_o");
        String body = mvc.perform(json(post("/api/v1/rooms"), owner, "{\"title\":\"인원 상한\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String roomId = json.readTree(body).get("roomId").asString();

        var members = new ArrayList<MockHttpSession>();
        for (int i = 1; i < size; i++) {
            var m = signUp(prefix + "_" + i);
            mvc.perform(joinRequest(m, roomId)).andExpect(status().isOk());
            members.add(m);
        }
        return new Room(roomId, owner, members);
    }

    private int memberCount(String roomId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM room_member WHERE room_id = ?::uuid", Integer.class, roomId);
    }

    private int bookmarkCount(String roomId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM bookmark WHERE room_id = ?::uuid", Integer.class, roomId);
    }

    /** 지도에서 찍은 후보. 같은 POI 를 두 번 담는 제약에 걸리지 않게 POI 없이 담는다. */
    private MockHttpServletRequestBuilder addBookmark(MockHttpSession session, String roomId, String name) {
        return json(post("/api/v1/rooms/" + roomId + "/bookmarks"), session,
                "{\"name\":\"%s\",\"lng\":127.01,\"lat\":37.52}".formatted(name));
    }

    /** 후보를 {@code count} 곳 담고 마지막 것의 id 를 돌려준다. */
    private long fillBookmarks(MockHttpSession session, String roomId, int count) throws Exception {
        long last = -1;
        for (int i = 0; i < count; i++) {
            String body = mvc.perform(addBookmark(session, roomId, "후보" + i))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            last = json.readTree(body).get("bookmarkId").asLong();
        }
        return last;
    }

    @Test
    @DisplayName("10명까지 들어오고 11번째는 409 — 꽉 찼다고 알려준다")
    void eleventhIsRejected() throws Exception {
        var room = roomOf(MAX_MEMBERS, "capfull");
        var late = signUp("capfull_late");

        mvc.perform(joinRequest(late, room.id()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("방이 꽉 찼습니다 (최대 " + MAX_MEMBERS + "명)"));

        assertThat(memberCount(room.id())).isEqualTo(MAX_MEMBERS);
    }

    @Test
    @DisplayName("초대 코드로 들어와도 같은 상한이다 — 문이 둘이라 둘 다 막아야 한다")
    void inviteCodeObeysTheCap() throws Exception {
        var room = roomOf(MAX_MEMBERS, "capcode");
        String body = mvc.perform(get("/api/v1/rooms/" + room.id()).session(room.owner()))
                .andReturn().getResponse().getContentAsString();
        String code = json.readTree(body).get("inviteCode").asString();
        var late = signUp("capcode_late");

        mvc.perform(json(post("/api/v1/rooms/join"), late, "{\"code\":\"%s\"}".formatted(code)))
                .andExpect(status().isConflict());

        assertThat(memberCount(room.id())).isEqualTo(MAX_MEMBERS);
    }

    @Test
    @DisplayName("꽉 찬 방이어도 이미 들어온 사람은 다시 들어온다 — 자리를 더 쓰지 않는다")
    void reentryIsAllowedWhenFull() throws Exception {
        var room = roomOf(MAX_MEMBERS, "capback");

        mvc.perform(joinRequest(room.members().getFirst(), room.id())).andExpect(status().isOk());

        assertThat(memberCount(room.id())).isEqualTo(MAX_MEMBERS);
    }

    @Test
    @DisplayName("한 명이 나가면 자리가 난다")
    void leavingFreesASeat() throws Exception {
        var room = roomOf(MAX_MEMBERS, "capleave");
        mvc.perform(delete("/api/v1/rooms/" + room.id() + "/members/me")
                        .session(room.members().getLast()).with(csrf()))
                .andExpect(status().isNoContent());

        mvc.perform(joinRequest(signUp("capleave_new"), room.id())).andExpect(status().isOk());

        assertThat(memberCount(room.id())).isEqualTo(MAX_MEMBERS);
    }

    @Test
    @DisplayName("후보는 30곳까지 담고 31번째는 409 — 어떻게 하면 되는지까지 알려준다")
    void thirtyFirstBookmarkIsRejected() throws Exception {
        var room = roomOf(1, "bmfull");
        fillBookmarks(room.owner(), room.id(), MAX_BOOKMARKS);

        mvc.perform(addBookmark(room.owner(), room.id(), "하나 더"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "후보는 한 방에 " + MAX_BOOKMARKS + "곳까지 담을 수 있습니다. 하나를 빼고 담아 주세요"));

        assertThat(bookmarkCount(room.id())).isEqualTo(MAX_BOOKMARKS);
    }

    @Test
    @DisplayName("후보를 하나 빼면 다시 담을 수 있다")
    void removingABookmarkFreesASlot() throws Exception {
        var room = roomOf(1, "bmfree");
        long last = fillBookmarks(room.owner(), room.id(), MAX_BOOKMARKS);

        mvc.perform(delete("/api/v1/rooms/" + room.id() + "/bookmarks/" + last)
                        .session(room.owner()).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(addBookmark(room.owner(), room.id(), "다시")).andExpect(status().isCreated());

        assertThat(bookmarkCount(room.id())).isEqualTo(MAX_BOOKMARKS);
    }

    /**
     * 29곳인 방에 여럿이 동시에 담는다. 인원과 같은 이유로 트랜잭션 밖에서 돌고 직접 지운다.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("빈 후보 자리 하나에 8명이 동시에 담아도 한 곳만 들어간다")
    void concurrentBookmarksDoNotOverfill() throws Exception {
        String tag = "cb" + Long.toString(System.nanoTime() % 1_000_000, 36);
        int contenders = 8;
        String roomId = null;
        try {
            var room = roomOf(contenders, tag);
            roomId = room.id();
            fillBookmarks(room.owner(), roomId, MAX_BOOKMARKS - 1);
            var people = new ArrayList<MockHttpSession>();
            people.add(room.owner());
            people.addAll(room.members());

            var start = new CountDownLatch(1);
            var statuses = new ConcurrentHashMap<Integer, Integer>();
            try (var pool = Executors.newFixedThreadPool(contenders)) {
                for (int i = 0; i < contenders; i++) {
                    int at = i;
                    String id = roomId;
                    pool.submit(() -> {
                        start.await();
                        int s = mvc.perform(addBookmark(people.get(at), id, "동시" + at))
                                .andReturn().getResponse().getStatus();
                        statuses.put(at, s);
                        return null;
                    });
                }
                start.countDown();
                pool.shutdown();
                assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            }

            assertThat(bookmarkCount(roomId)).isEqualTo(MAX_BOOKMARKS);
            assertThat(statuses.values().stream().filter(s -> s == 201).count()).isEqualTo(1);
            assertThat(statuses.values().stream().filter(s -> s == 409).count()).isEqualTo(contenders - 1);
        } finally {
            if (roomId != null) jdbc.update("DELETE FROM room WHERE id = ?::uuid", roomId);
            jdbc.update("DELETE FROM app_user WHERE login_id LIKE ?", tag + "\\_%");
        }
    }

    /**
     * 빈자리 하나에 여럿이 동시에 들어온다.
     *
     * <p>"세고 나서 넣기"는 둘이 동시에 9 를 보면 둘 다 들어와 11 명이 된다. 그걸 보려면 요청들이
     * 실제로 커밋되며 부딪혀야 하므로 이 테스트만 트랜잭션 밖에서 돌고, 만든 것을 직접 지운다.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("빈자리 하나에 8명이 동시에 들어와도 한 명만 들어간다")
    void concurrentJoinsDoNotOverfill() throws Exception {
        String tag = "cr" + Long.toString(System.nanoTime() % 1_000_000, 36);
        int contenders = 8;
        String roomId = null;
        try {
            var room = roomOf(MAX_MEMBERS - 1, tag);
            roomId = room.id();
            var people = new ArrayList<MockHttpSession>();
            for (int i = 0; i < contenders; i++) people.add(signUp(tag + "_c" + i));

            var start = new CountDownLatch(1);
            var statuses = new ConcurrentHashMap<Integer, Integer>();
            try (var pool = Executors.newFixedThreadPool(contenders)) {
                for (int i = 0; i < contenders; i++) {
                    int at = i;
                    String id = roomId;
                    pool.submit(() -> {
                        start.await();
                        int s = mvc.perform(joinRequest(people.get(at), id)).andReturn().getResponse().getStatus();
                        statuses.put(at, s);
                        return null;
                    });
                }
                start.countDown();
                pool.shutdown();
                assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            }

            long admitted = statuses.values().stream().filter(s -> s == 200).count();
            long refused = statuses.values().stream().filter(s -> s == 409).count();
            assertThat(memberCount(roomId))
                    .as("동시에 들어온 뒤의 인원 — 상한을 넘으면 세고 넣는 사이에 끼어든 것이다")
                    .isEqualTo(MAX_MEMBERS);
            assertThat(admitted).isEqualTo(1);
            assertThat(refused).isEqualTo(contenders - 1);
        } finally {
            if (roomId != null) jdbc.update("DELETE FROM room WHERE id = ?::uuid", roomId);
            jdbc.update("DELETE FROM app_user WHERE login_id LIKE ?", tag + "\\_%");
        }
    }
}
