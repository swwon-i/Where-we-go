package com.wherewego.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
 * 방 만들기 → 참가 → 출발지 → 북마크.
 *
 * <p>두 사람({@code 주인}, {@code 손님})을 각각 다른 세션으로 두고 돈다. 권한 규칙은
 * <b>남이 시도했을 때</b>만 드러나므로 한 사람으로는 검증되지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@EnabledIf("databaseIsUp")
class RoomFlowTest {

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
    private MockHttpSession guest;

    @BeforeEach
    void signUpTwoPeople() throws Exception {
        owner = signUp("방주인", "room_owner");
        guest = signUp("손님", "room_guest");
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

    // ── 방 ──────────────────────────────────────────────────────────────────

    @Nested
    class Rooms {

        @Test
        @DisplayName("방을 만들면 만든 사람은 자동으로 참가한다 — 따로 들어가게 할 이유가 없다")
        void creatorIsAMember() throws Exception {
            var roomId = createRoom(owner);
            mvc.perform(get("/api/v1/rooms/" + roomId).session(owner))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.iAmOwner").value(true))
                    .andExpect(jsonPath("$.members.length()").value(1))
                    .andExpect(jsonPath("$.members[0].nickname").value("방주인"));
        }

        @Test
        @DisplayName("참가하지 않은 사람은 방을 못 본다 — 링크는 초대장이지 열람권이 아니다")
        void nonMemberCannotView() throws Exception {
            var roomId = createRoom(owner);
            mvc.perform(get("/api/v1/rooms/" + roomId).session(guest))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("참가하면 그때부터 보인다")
        void joiningGrantsAccess() throws Exception {
            var roomId = createRoom(owner);
            join(guest, roomId);

            mvc.perform(get("/api/v1/rooms/" + roomId).session(guest))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.iAmOwner").value(false))
                    .andExpect(jsonPath("$.members.length()").value(2));
        }

        @Test
        @DisplayName("두 번 참가해도 참가자가 늘지 않는다 — 다시 누르는 것이 오류일 이유가 없다")
        void joiningTwiceIsIdempotent() throws Exception {
            var roomId = createRoom(owner);
            join(guest, roomId);
            join(guest, roomId);

            mvc.perform(get("/api/v1/rooms/" + roomId).session(guest))
                    .andExpect(jsonPath("$.members.length()").value(2));
        }

        @Test
        @DisplayName("없는 방은 404")
        void unknownRoomIs404() throws Exception {
            mvc.perform(get("/api/v1/rooms/00000000-0000-0000-0000-000000000000").session(owner))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("로그인하지 않으면 방을 만들 수 없다")
        void anonymousCannotCreate() throws Exception {
            mvc.perform(json(post("/api/v1/rooms"), new MockHttpSession(),
                            "{\"title\":\"익명의 방\"}"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("방 이름은 비울 수 없다")
        void titleIsRequired() throws Exception {
            mvc.perform(json(post("/api/v1/rooms"), owner, "{\"title\":\"   \"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fields.title").exists());
        }
    }

    // ── 출발지 ──────────────────────────────────────────────────────────────

    @Nested
    class Origins {

        @Test
        @DisplayName("출발지를 정하면 스냅 결과를 숨기지 않고 돌려준다")
        void originReturnsSnapResult() throws Exception {
            var roomId = createRoom(owner);

            mvc.perform(json(put("/api/v1/rooms/" + roomId + "/members/me/origin"), owner,
                            """
                            {"lng":127.0276,"lat":37.4979,"label":"강남역"}
                            """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.label").value("강남역"))
                    // 스냅 거리가 크면 결과를 덜 믿어야 한다. 그것을 아는 것은 사용자의 권리다.
                    .andExpect(jsonPath("$.snapDistanceM").isNumber())
                    .andExpect(jsonPath("$.graphBuildId").isNumber())
                    .andExpect(jsonPath("$.graphNodeId").isNumber());
        }

        @Test
        @DisplayName("역 이름으로도 정할 수 있다")
        void originByStationName() throws Exception {
            var roomId = createRoom(owner);
            mvc.perform(json(put("/api/v1/rooms/" + roomId + "/members/me/origin"), owner,
                            "{\"station\":\"강남\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.label").value("강남"));
        }

        @Test
        @DisplayName("좌표도 역 이름도 없으면 400")
        void originNeedsSomething() throws Exception {
            var roomId = createRoom(owner);
            mvc.perform(json(put("/api/v1/rooms/" + roomId + "/members/me/origin"), owner, "{}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("서울 밖 좌표는 붙일 곳이 없어 409")
        void unsnappableOriginIs409() throws Exception {
            var roomId = createRoom(owner);
            mvc.perform(json(put("/api/v1/rooms/" + roomId + "/members/me/origin"), owner,
                            """
                            {"lng":129.0756,"lat":35.1796,"label":"부산"}
                            """))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("참가하지 않은 방에는 출발지를 못 정한다")
        void nonMemberCannotSetOrigin() throws Exception {
            var roomId = createRoom(owner);
            mvc.perform(json(put("/api/v1/rooms/" + roomId + "/members/me/origin"), guest,
                            "{\"station\":\"강남\"}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("내 출발지만 보이는 게 아니라 남의 것도 보인다 — 행렬의 행이 된다")
        void othersOriginsAreVisible() throws Exception {
            var roomId = createRoom(owner);
            join(guest, roomId);
            mvc.perform(json(put("/api/v1/rooms/" + roomId + "/members/me/origin"), guest,
                            "{\"station\":\"홍대입구\"}"))
                    .andExpect(status().isOk());

            mvc.perform(get("/api/v1/rooms/" + roomId).session(owner))
                    .andExpect(jsonPath("$.members[?(@.nickname=='손님')].originSet").value(true))
                    .andExpect(jsonPath("$.members[?(@.nickname=='손님')].originLabel")
                            .value("홍대입구"))
                    .andExpect(jsonPath("$.members[?(@.nickname=='방주인')].originSet")
                            .value(false));
        }
    }

    // ── 북마크 ──────────────────────────────────────────────────────────────

    @Nested
    class Bookmarks {

        private long anyPoiId() {
            return jdbc.queryForObject(
                    "SELECT id FROM poi WHERE status = 'ACTIVE' ORDER BY id LIMIT 1", Long.class);
        }

        @Test
        @DisplayName("POI 를 담으면 그 시점의 이름·좌표가 복사된다 — 나중에 폐업해도 방이 안 깨진다")
        void poiBookmarkSnapshotsNameAndPoint() throws Exception {
            var roomId = createRoom(owner);
            long poiId = anyPoiId();
            String originalName = jdbc.queryForObject(
                    "SELECT name FROM poi WHERE id = ?", String.class, poiId);

            mvc.perform(json(post("/api/v1/rooms/" + roomId + "/bookmarks"), owner,
                            "{\"poiId\":%d}".formatted(poiId)))
                    .andExpect(status().isCreated());

            // 원본 이름을 바꿔도 북마크는 따라가지 않는다
            jdbc.update("UPDATE poi SET name = '바뀐이름' WHERE id = ?", poiId);

            mvc.perform(get("/api/v1/rooms/" + roomId).session(owner))
                    .andExpect(jsonPath("$.bookmarks[0].name").value(originalName))
                    .andExpect(jsonPath("$.bookmarks[0].poiId").value(poiId));
        }

        @Test
        @DisplayName("지도를 찍어 직접 넣을 수도 있다 — 우리 POI 에 없는 곳도 후보가 된다")
        void pointBookmark() throws Exception {
            var roomId = createRoom(owner);
            mvc.perform(json(post("/api/v1/rooms/" + roomId + "/bookmarks"), owner,
                            """
                            {"name":"한강 둔치","lng":127.0100,"lat":37.5200}
                            """))
                    .andExpect(status().isCreated());

            mvc.perform(get("/api/v1/rooms/" + roomId).session(owner))
                    .andExpect(jsonPath("$.bookmarks[0].name").value("한강 둔치"))
                    .andExpect(jsonPath("$.bookmarks[0].poiId").doesNotExist());
        }

        @Test
        @DisplayName("같은 POI 를 두 번 담으면 409")
        void duplicatePoiBookmark() throws Exception {
            var roomId = createRoom(owner);
            String body = "{\"poiId\":%d}".formatted(anyPoiId());

            mvc.perform(json(post("/api/v1/rooms/" + roomId + "/bookmarks"), owner, body))
                    .andExpect(status().isCreated());
            mvc.perform(json(post("/api/v1/rooms/" + roomId + "/bookmarks"), owner, body))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("담은 사람만 뺄 수 있다")
        void onlyTheAdderCanRemove() throws Exception {
            var roomId = createRoom(owner);
            join(guest, roomId);

            var created = mvc.perform(json(post("/api/v1/rooms/" + roomId + "/bookmarks"), owner,
                            "{\"name\":\"주인이 담은 곳\",\"lng\":127.01,\"lat\":37.52}"))
                    .andReturn().getResponse().getContentAsString();
            long bookmarkId = json.readTree(created).get("bookmarkId").asLong();

            mvc.perform(delete("/api/v1/rooms/" + roomId + "/bookmarks/" + bookmarkId)
                            .session(guest).with(csrf()))
                    .andExpect(status().isForbidden());

            mvc.perform(delete("/api/v1/rooms/" + roomId + "/bookmarks/" + bookmarkId)
                            .session(owner).with(csrf()))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("참가하지 않은 사람은 담을 수 없다")
        void nonMemberCannotAdd() throws Exception {
            var roomId = createRoom(owner);
            mvc.perform(json(post("/api/v1/rooms/" + roomId + "/bookmarks"), guest,
                            "{\"name\":\"끼어들기\",\"lng\":127.01,\"lat\":37.52}"))
                    .andExpect(status().isForbidden());
        }
    }

    // ── matrixReady ─────────────────────────────────────────────────────────

    @Nested
    class MatrixReady {

        @Test
        @DisplayName("출발지와 후보가 둘 다 있어야 참이다 — 계산 완료가 아니라 계산 가능 여부다")
        void needsBothSides() throws Exception {
            var roomId = createRoom(owner);

            mvc.perform(get("/api/v1/rooms/" + roomId).session(owner))
                    .andExpect(jsonPath("$.matrixReady").value(false));

            mvc.perform(json(put("/api/v1/rooms/" + roomId + "/members/me/origin"), owner,
                            "{\"station\":\"강남\"}"))
                    .andExpect(status().isOk());
            mvc.perform(get("/api/v1/rooms/" + roomId).session(owner))
                    .andExpect(jsonPath("$.matrixReady").value(false));

            mvc.perform(json(post("/api/v1/rooms/" + roomId + "/bookmarks"), owner,
                            "{\"name\":\"후보\",\"lng\":127.01,\"lat\":37.52}"))
                    .andExpect(status().isCreated());
            mvc.perform(get("/api/v1/rooms/" + roomId).session(owner))
                    .andExpect(jsonPath("$.matrixReady").value(true));
        }
    }

    @Test
    @DisplayName("내가 들어간 방만 목록에 나온다")
    void myRoomsListsOnlyMine() throws Exception {
        var mine = createRoom(owner);
        createRoom(guest);

        var body = mvc.perform(get("/api/v1/rooms").session(owner))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var rooms = json.readTree(body);
        assertThat(rooms.size()).isEqualTo(1);
        assertThat(rooms.get(0).get("roomId").asString()).isEqualTo(mine);
    }
}
