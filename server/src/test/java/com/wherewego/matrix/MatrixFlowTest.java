package com.wherewego.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 이동시간 행렬. 실제 그래프가 필요하므로 DB 가 없으면 건너뛴다.
 *
 * <p>두 사람이 서로 다른 곳에서 출발하고 후보가 여럿인 상황을 만들어 돌린다 —
 * 행렬의 성질은 <b>축이 둘 다 하나보다 클 때</b>만 드러난다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@EnabledIf("databaseIsUp")
class MatrixFlowTest {

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

    private final JsonMapper json = JsonMapper.builder().build();

    private MockHttpSession owner;
    private MockHttpSession guest;
    private String roomId;

    @BeforeEach
    void setUpRoom() throws Exception {
        owner = signUp("행렬주인", "matrix_owner");
        guest = signUp("행렬손님", "matrix_guest");

        roomId = createRoom();
        join(guest);

        setOrigin(owner, "{\"station\":\"강남\"}");
        setOrigin(guest, "{\"station\":\"왕십리\"}");
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    private MockHttpSession signUp(String nickname, String loginId) throws Exception {
        var session = new MockHttpSession();
        mvc.perform(body(post("/api/v1/auth/signup"), session, """
                        {"nickname":"%s","loginId":"%s","password":"%s","passwordConfirm":"%s"}
                        """.formatted(nickname, loginId, PW, PW)))
                .andExpect(status().isCreated());
        return session;
    }

    private MockHttpServletRequestBuilder body(
            MockHttpServletRequestBuilder builder, MockHttpSession session, String content) {
        return builder.session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(content);
    }

    private String createRoom() throws Exception {
        var response = mvc.perform(body(post("/api/v1/rooms"), owner, "{\"title\":\"행렬 방\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("roomId").asString();
    }

    private void join(MockHttpSession session) throws Exception {
        mvc.perform(post("/api/v1/rooms/" + roomId + "/members").session(session).with(csrf()))
                .andExpect(status().isOk());
    }

    private void setOrigin(MockHttpSession session, String content) throws Exception {
        mvc.perform(body(put("/api/v1/rooms/" + roomId + "/members/me/origin"), session, content))
                .andExpect(status().isOk());
    }

    private void addBookmark(String name, double lng, double lat) throws Exception {
        mvc.perform(body(post("/api/v1/rooms/" + roomId + "/bookmarks"), owner,
                        """
                        {"name":"%s","lng":%s,"lat":%s}
                        """.formatted(name, lng, lat)))
                .andExpect(status().isCreated());
    }

    private JsonNode matrix(Integer hour) throws Exception {
        String content = hour == null ? "{}" : "{\"departureHour\":%d}".formatted(hour);
        var response = mvc.perform(body(post("/api/v1/rooms/" + roomId + "/matrix"), owner, content))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    /** 서울 안의 후보 셋. 좌표는 각각 시청·홍대입구·잠실 인근이다. */
    private void addThreeBookmarks() throws Exception {
        addBookmark("시청 근처", 126.9779, 37.5663);
        addBookmark("홍대 근처", 126.9240, 37.5563);
        addBookmark("잠실 근처", 127.1000, 37.5133);
    }

    // ── 모양 ────────────────────────────────────────────────────────────────

    @Nested
    class Shape {

        @Test
        @DisplayName("행이 장소, 열이 사람이다 — 한 줄을 훑으면 그 장소가 모두에게 어떤지 보인다")
        void rowsArePlacesColumnsArePeople() throws Exception {
            addThreeBookmarks();
            var m = matrix(null);

            assertThat(m.get("origins").size()).isEqualTo(2);
            assertThat(m.get("rows").size()).isEqualTo(3);

            // 각 줄의 칸 수가 사람 수와 같아야 표가 된다
            for (var row : m.get("rows")) {
                assertThat(row.get("cells").size()).isEqualTo(2);
            }
        }

        @Test
        @DisplayName("칸에 소요시간·환승·도보거리가 함께 나온다 — 순위는 매기지 않는다")
        void cellCarriesTheNumbersNotARank() throws Exception {
            addThreeBookmarks();
            var cell = matrix(null).get("rows").get(0).get("cells").get(0);

            assertThat(cell.get("reachable").asBoolean()).isTrue();
            assertThat(cell.get("durationSeconds").asInt()).isPositive();
            assertThat(cell.get("transfers").asInt()).isNotNegative();
            assertThat(cell.get("walkDistanceM").asDouble()).isNotNegative();
            assertThat(cell.get("summary").asString()).isNotBlank();
        }

        @Test
        @DisplayName("출발지의 스냅 거리를 열에 그대로 싣는다 — 숨기면 결과를 얼마나 믿을지 알 수 없다")
        void originsCarrySnapDistance() throws Exception {
            addThreeBookmarks();
            for (var origin : matrix(null).get("origins")) {
                assertThat(origin.get("snapDistanceM").asDouble()).isNotNegative();
                assertThat(origin.get("nickname").asString()).isNotBlank();
            }
        }
    }

    // ── one-to-many ─────────────────────────────────────────────────────────

    @Nested
    class OneToMany {

        @Test
        @DisplayName("후보가 늘어도 탐색 횟수는 그대로다 — 출발지 수와만 같다")
        void runsTrackOriginsNotDestinations() throws Exception {
            addBookmark("후보 하나", 126.9779, 37.5663);
            int withOne = matrix(null).get("stats").get("dijkstraRuns").asInt();

            addBookmark("후보 둘", 126.9240, 37.5563);
            addBookmark("후보 셋", 127.1000, 37.5133);
            var three = matrix(null);

            assertThat(three.get("rows").size()).isEqualTo(3);
            // 후보가 1개에서 3개로 늘었는데 탐색은 그대로 2회(사람 수)다.
            // 늘었다면 후보마다 one-to-one 을 돌고 있다는 뜻이다.
            assertThat(three.get("stats").get("dijkstraRuns").asInt())
                    .isEqualTo(withOne)
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("stats 에 성능 서사가 실린다")
        void statsCarryTheStory() throws Exception {
            addThreeBookmarks();
            var stats = matrix(null).get("stats");

            assertThat(stats.get("expandedNodes").asInt()).isPositive();
            assertThat(stats.get("elapsedMs").asLong()).isNotNegative();
            assertThat(stats.get("graphSource").asString()).isIn("CACHE", "DB");
            assertThat(stats.get("graphBuildId").asLong()).isPositive();
        }
    }

    // ── 시간대 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("시간대를 바꾸면 결과가 달라진다 — departureHour 가 장식이 아니다")
    void departureHourChangesTheNumbers() throws Exception {
        addThreeBookmarks();

        var morning = matrix(8);
        var night = matrix(23);

        boolean anyDifferent = false;
        for (int r = 0; r < morning.get("rows").size(); r++) {
            for (int c = 0; c < 2; c++) {
                var a = morning.get("rows").get(r).get("cells").get(c);
                var b = night.get("rows").get(r).get("cells").get(c);
                if (a.get("reachable").asBoolean() && b.get("reachable").asBoolean()
                        && a.get("durationSeconds").asInt() != b.get("durationSeconds").asInt()) {
                    anyDifferent = true;
                }
            }
        }
        assertThat(anyDifferent).as("8시와 23시가 전부 같다면 시간대 가중치를 의심할 것").isTrue();
    }

    // ── 실패의 범위 ─────────────────────────────────────────────────────────

    @Nested
    class Failures {

        @Test
        @DisplayName("서울 밖 후보는 그 줄만 실패한다 — 나머지 행렬은 그대로 쓸모가 있다")
        void unreachableDestinationFailsOnlyItsOwnRow() throws Exception {
            addBookmark("시청 근처", 126.9779, 37.5663);
            addBookmark("부산", 129.0756, 35.1796);

            var m = matrix(null);
            assertThat(m.get("rows").size()).isEqualTo(2);

            var seoul = m.get("rows").get(0);
            var busan = m.get("rows").get(1);

            assertThat(seoul.get("cells").get(0).get("reachable").asBoolean()).isTrue();
            assertThat(busan.get("cells").get(0).get("reachable").asBoolean()).isFalse();
            assertThat(busan.get("cells").get(0).get("reason").asString())
                    .isEqualTo("NO_WALK_NETWORK");
        }

        @Test
        @DisplayName("후보가 없으면 422 — 요청이 틀린 게 아니라 방이 아직 준비되지 않은 것이다")
        void noBookmarksIs422() throws Exception {
            mvc.perform(body(post("/api/v1/rooms/" + roomId + "/matrix"), owner, "{}"))
                    .andExpect(status().isUnprocessableContent());
        }

        @Test
        @DisplayName("참가하지 않은 사람은 계산할 수 없다")
        void nonMemberCannotCompute() throws Exception {
            var stranger = signUp("구경꾼", "matrix_stranger");
            addThreeBookmarks();

            mvc.perform(body(post("/api/v1/rooms/" + roomId + "/matrix"), stranger, "{}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("departureHour 가 범위를 벗어나면 400")
        void badHourIsRejected() throws Exception {
            addThreeBookmarks();
            mvc.perform(body(post("/api/v1/rooms/" + roomId + "/matrix"), owner,
                            "{\"departureHour\":25}"))
                    .andExpect(status().isBadRequest());
        }
    }

    // ── 칸 하나의 상세 ──────────────────────────────────────────────────────

    @Nested
    class Detail {

        @Test
        @DisplayName("칸을 누르면 같은 값의 경로 상세가 나온다 — 행렬과 어긋나면 안 된다")
        void detailMatchesTheCell() throws Exception {
            addThreeBookmarks();
            var m = matrix(8);

            long memberId = m.get("origins").get(0).get("memberId").asLong();
            var row = m.get("rows").get(0);
            long bookmarkId = row.get("bookmarkId").asLong();
            int cellSeconds = row.get("cells").get(0).get("durationSeconds").asInt();

            var response = mvc.perform(get("/api/v1/rooms/" + roomId + "/routes")
                            .session(owner)
                            .param("originMemberId", String.valueOf(memberId))
                            .param("bookmarkId", String.valueOf(bookmarkId))
                            .param("departureHour", "8"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            var detail = json.readTree(response);
            assertThat(detail.get("totalSeconds").asInt()).isEqualTo(cellSeconds);
            assertThat(detail.get("legs").size()).isPositive();
            assertThat(detail.get("reachable").asBoolean()).isTrue();
        }

        @Test
        @DisplayName("없는 후보를 물으면 404")
        void unknownBookmarkIs404() throws Exception {
            addThreeBookmarks();
            long memberId = matrix(null).get("origins").get(0).get("memberId").asLong();

            mvc.perform(get("/api/v1/rooms/" + roomId + "/routes")
                            .session(owner)
                            .param("originMemberId", String.valueOf(memberId))
                            .param("bookmarkId", "999999"))
                    .andExpect(status().isNotFound());
        }
    }
}
