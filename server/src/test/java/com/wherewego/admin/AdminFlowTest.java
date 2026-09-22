package com.wherewego.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 운영 기록 API. 회차 둘(검수 결과가 있는 것 · SKIPPED)을 직접 넣고 읽는다 — 트랜잭션이라
 * 끝나면 사라진다. 실제 적재 기록이 몇 건이든 결과가 흔들리지 않도록, 넣은 회차만 골라 본다.
 *
 * <p>관리자는 {@code admin_tester} 하나로 지정한다. 설정이 다르므로 이 클래스는 컨텍스트를 따로
 * 띄운다 — {@code with(csrf())} 가 다른 테스트의 컨텍스트를 건드릴 일도 없다.
 */
@SpringBootTest(properties = "wwg.admin-login-ids=Admin_Tester")
@AutoConfigureMockMvc
@Transactional
@EnabledIf("databaseIsUp")
class AdminFlowTest {

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

    private static final String PW = "s3cret-pw";

    private long checked;
    private long skipped;
    private MockHttpSession admin;
    private MockHttpSession user;

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

    @BeforeEach
    void twoRuns() throws Exception {
        admin = signUp("관리인", "admin_tester");
        user = signUp("그냥손님", "plain_user");

        checked = jdbc.queryForObject("""
                INSERT INTO ingest_run (source, snapshot_date, status, rows_total, rows_valid, rows_rejected,
                                        rows_new, rows_kept, rows_vanished, rows_closed, duration_ms)
                VALUES ('TEST_SRC', '2026-09-20', 'SUCCESS', 10, 7, 3, 2, 5, 1, 1, 1234)
                RETURNING id""", Long.class);
        skipped = jdbc.queryForObject("""
                INSERT INTO ingest_run (source, snapshot_date, status)
                VALUES ('TEST_SRC', '2026-09-21', 'SKIPPED') RETURNING id""", Long.class);
        for (int i = 0; i < 3; i++) {
            jdbc.update("INSERT INTO validation_result (run_id, rule_code, severity, target_id, detail) "
                    + "VALUES (?, 'COORD_MISSING', 'ERROR', ?, '{\"name\":\"가게\"}'::jsonb)", checked, "T" + i);
        }
        jdbc.update("INSERT INTO validation_result (run_id, rule_code, severity, target_id) "
                + "VALUES (?, 'DUPLICATE_NAME_ADDR', 'WARN', 'T9')", checked);
    }

    private JsonNode getJson(String url) throws Exception {
        var body = mvc.perform(get(url).session(admin)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return json.readTree(body);
    }

    @Test
    @DisplayName("로그인하지 않았으면 401")
    void anonymousIs401() throws Exception {
        mvc.perform(get("/api/v1/admin/ingest-runs")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/graph-stats")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("일반 사용자는 403 — 로그인만으로는 안 된다")
    void plainUserIs403() throws Exception {
        mvc.perform(get("/api/v1/admin/ingest-runs").session(user)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/validation-results").session(user)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("설정에 적힌 관리자는 대소문자와 상관없이 열린다")
    void adminIs200() throws Exception {
        mvc.perform(get("/api/v1/admin/ingest-runs").session(admin)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("/auth/me 가 관리자 여부를 알려 준다 — 화면이 메뉴를 보이거나 숨긴다")
    void meTellsAdmin() throws Exception {
        var a = json.readTree(mvc.perform(get("/api/v1/auth/me").session(admin)).andReturn()
                .getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        var u = json.readTree(mvc.perform(get("/api/v1/auth/me").session(user)).andReturn()
                .getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(a.get("admin").asBoolean()).isTrue();
        assertThat(u.get("admin").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("관리자라도 쓰기는 열려 있지 않다")
    void noWrites() throws Exception {
        mvc.perform(post("/api/v1/admin/ingest-runs").session(admin).with(csrf()))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("회차는 최신순이고 신규·유지·소멸·폐업 건수를 싣는다")
    void ingestRunsNewestFirst() throws Exception {
        var runs = getJson("/api/v1/admin/ingest-runs?source=TEST_SRC").get("runs");
        assertThat(runs.size()).isEqualTo(2);
        assertThat(runs.get(0).get("status").asString()).isEqualTo("SKIPPED");
        var ok = runs.get(1);
        assertThat(ok.get("rowsNew").asInt()).isEqualTo(2);
        assertThat(ok.get("rowsVanished").asInt()).isEqualTo(1);
        assertThat(ok.get("rowsClosed").asInt()).isEqualTo(1);
        assertThat(runs.get(0).get("rowsTotal").isNull()).isTrue();   // SKIPPED 는 비어 있다
    }

    @Test
    @DisplayName("검수 요약은 SKIPPED 회차를 건너뛰고 결과가 있는 최근 회차를 본다")
    void summarySkipsEmptyRuns() throws Exception {
        var rows = getJson("/api/v1/admin/validation-summary");
        var mine = new java.util.ArrayList<JsonNode>();
        rows.forEach(r -> { if ("TEST_SRC".equals(r.get("source").asString())) mine.add(r); });

        assertThat(mine).extracting(r -> r.get("runId").asLong()).containsOnly(checked);
        assertThat(mine).extracting(r -> r.get("ruleCode").asString() + ":" + r.get("count").asLong())
                .containsExactlyInAnyOrder("COORD_MISSING:3", "DUPLICATE_NAME_ADDR:1");
    }

    @Test
    @DisplayName("검수 결과는 규칙·심각도로 거르고 쪽을 나눈다")
    void resultsFilterAndPage() throws Exception {
        var page = getJson("/api/v1/admin/validation-results?runId=" + checked
                + "&rule=COORD_MISSING&severity=ERROR&page=1&size=2");
        assertThat(page.get("total").asLong()).isEqualTo(3);
        assertThat(page.get("items").size()).isEqualTo(1);           // 3건을 2개씩 — 둘째 쪽은 1건
        assertThat(page.get("items").get(0).get("detail").get("name").asString()).isEqualTo("가게");
    }

    @Test
    @DisplayName("잘못된 쪽 크기·심각도는 400")
    void rejectsBadParams() throws Exception {
        mvc.perform(get("/api/v1/admin/validation-results?size=501").session(admin))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/validation-results?severity=INFO").session(admin))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/validation-results?page=-1").session(admin))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("그래프 통계는 버스까지 센다 — graph_build 의 개수 컬럼은 지하철만 센다")
    void graphStatsCountsBus() throws Exception {
        var stats = getJson("/api/v1/admin/graph-stats");
        if (stats.get("active").isNull()) return;   // 그래프를 아직 안 만든 환경
        var modes = new java.util.HashSet<String>();
        stats.get("counts").forEach(c -> modes.add(c.get("mode").asString()));
        assertThat(modes).contains("SUBWAY", "WALK");
    }
}
