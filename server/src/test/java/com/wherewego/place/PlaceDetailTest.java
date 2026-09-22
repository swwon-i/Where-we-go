package com.wherewego.place;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 장소 상세. 회차 둘(두 원천)과 장소 하나를 직접 넣는다 — 트랜잭션이라 끝나면 사라진다.
 *
 * <p>두 원천에 <b>같은 인허가번호</b>를 일부러 쓴다. 번호만으로 검수 기록을 이으면 다른 원천의
 * 기록이 섞인다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@EnabledIf("databaseIsUp")
class PlaceDetailTest {

    static boolean databaseIsUp() {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 5432), 300);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static final String LICENSE = "TEST-101-2026-00001";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private final JsonMapper json = JsonMapper.builder().build();

    private long poiId;
    private long foodRun;

    @BeforeEach
    void seed() {
        foodRun = jdbc.queryForObject("""
                INSERT INTO ingest_run (source, snapshot_date, status)
                VALUES ('TEST_FOOD', '2026-09-20', 'SUCCESS') RETURNING id""", Long.class);
        long restRun = jdbc.queryForObject("""
                INSERT INTO ingest_run (source, snapshot_date, status)
                VALUES ('TEST_REST', '2026-09-20', 'SUCCESS') RETURNING id""", Long.class);

        poiId = jdbc.queryForObject("""
                INSERT INTO poi (source, source_id, name, road_address, status, licensed_date, closed_date,
                                 geom, first_seen_run_id, last_seen_run_id, validated_run_id)
                VALUES ('TEST_FOOD', ?, '시험식당', '서울특별시 중구 세종대로 110', 'CLOSED',
                        '2020-01-02', '2025-12-31',
                        ST_Transform(ST_SetSRID(ST_MakePoint(126.9780, 37.5665), 4326), 5186), ?, ?, ?)
                RETURNING id""", Long.class, LICENSE, foodRun, foodRun, foodRun);

        jdbc.update("INSERT INTO validation_result (run_id, rule_code, severity, target_id, detail) "
                + "VALUES (?, 'DUPLICATE_NAME_ADDR', 'WARN', ?, '{\"group_size\": 2}'::jsonb)", foodRun, LICENSE);
        // 다른 원천의 같은 번호 — 섞이면 안 된다
        jdbc.update("INSERT INTO validation_result (run_id, rule_code, severity, target_id) "
                + "VALUES (?, 'COORD_MISSING', 'ERROR', ?)", restRun, LICENSE);
    }

    private JsonNode detail(long id) throws Exception {
        var body = mvc.perform(get("/api/v1/places/" + id)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(body);
    }

    @Test
    @DisplayName("로그인 없이 읽힌다 — 폐업한 곳도 돌려준다")
    void closedPlaceIsReturned() throws Exception {
        var d = detail(poiId);
        assertThat(d.get("place").get("name").asString()).isEqualTo("시험식당");
        assertThat(d.get("place").get("status").asString()).isEqualTo("CLOSED");
        assertThat(d.get("closedDate").asString()).isEqualTo("2025-12-31");
    }

    @Test
    @DisplayName("좌표는 응답용 4326 이다")
    void coordinatesAreWgs84() throws Exception {
        var p = detail(poiId).get("place");
        assertThat(p.get("lng").asDouble()).isCloseTo(126.9780, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(p.get("lat").asDouble()).isCloseTo(37.5665, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    @DisplayName("출처와 처음·마지막으로 보인 회차를 싣는다")
    void provenance() throws Exception {
        var d = detail(poiId);
        assertThat(d.get("source").asString()).isEqualTo("TEST_FOOD");
        assertThat(d.get("sourceId").asString()).isEqualTo(LICENSE);
        assertThat(d.get("firstSeen").get("runId").asLong()).isEqualTo(foodRun);
        assertThat(d.get("lastSeen").get("snapshotDate").asString()).isEqualTo("2026-09-20");
    }

    @Test
    @DisplayName("검수 기록은 같은 원천의 것만 — 인허가번호가 같아도 다른 원천은 섞지 않는다")
    void validationsFromSameSourceOnly() throws Exception {
        var v = detail(poiId).get("validations");
        assertThat(v.size()).isEqualTo(1);
        assertThat(v.get(0).get("ruleCode").asString()).isEqualTo("DUPLICATE_NAME_ADDR");
        assertThat(v.get(0).get("detail").get("group_size").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("없는 장소는 404")
    void missingIs404() throws Exception {
        mvc.perform(get("/api/v1/places/" + Long.MAX_VALUE)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("숫자가 아니면 400 — /nearby · /search 와 겹치지 않는다")
    void nonNumericIs400() throws Exception {
        mvc.perform(get("/api/v1/places/abc")).andExpect(status().isBadRequest());
    }
}
