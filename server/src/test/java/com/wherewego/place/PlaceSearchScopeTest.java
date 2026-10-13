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
 * 상호명 검색의 범위.
 *
 * <p>강남을 보는 중에 강서구 가게를 찾을 수 있어야 한다. 이름을 치는 사람은 그 가게가 지금
 * 보는 화면 밖에 있어도 찾으려는 것이다 — 반경에 가두면 검색이 고장 난 것처럼 보인다.
 * 반경은 주었을 때만 좁힌다.
 *
 * <p>멀리 떨어진 두 곳을 직접 넣는다. 트랜잭션이라 끝나면 사라진다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@EnabledIf("databaseIsUp")
class PlaceSearchScopeTest {

    static boolean databaseIsUp() {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 5432), 300);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 실제 자료에 없는 말이라야 한다 — 있으면 이 시험이 남의 데이터를 같이 센다. */
    private static final String TOKEN = "검색범위시험";

    private static final double GANGNAM_LNG = 127.0276;
    private static final double GANGNAM_LAT = 37.4979;
    private static final double GANGSEO_LNG = 126.8495;
    private static final double GANGSEO_LAT = 37.5509;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void seed() {
        long run = jdbc.queryForObject("""
                INSERT INTO ingest_run (source, snapshot_date, status)
                VALUES ('TEST_SCOPE', '2026-10-13', 'SUCCESS') RETURNING id""", Long.class);
        insert(run, "1", TOKEN + " 강남점", GANGNAM_LNG, GANGNAM_LAT);
        insert(run, "2", TOKEN + " 강서점", GANGSEO_LNG, GANGSEO_LAT);
    }

    private void insert(long run, String sourceId, String name, double lng, double lat) {
        jdbc.update("""
                INSERT INTO poi (source, source_id, name, status, geom,
                                 first_seen_run_id, last_seen_run_id, validated_run_id)
                VALUES ('TEST_SCOPE', ?, ?, 'ACTIVE',
                        ST_Transform(ST_SetSRID(ST_MakePoint(?, ?), 4326), 5186), ?, ?, ?)
                """, sourceId, name, lng, lat, run, run, run);
    }

    /** 이름·값을 번갈아 받는다. 한글을 질의 문자열에 직접 붙이지 않으려는 것이다. */
    private JsonNode search(String... params) throws Exception {
        var request = get("/api/v1/places/search");
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        var body = mvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(body);
    }

    /** 지도 중심이 강남일 때 보내는 것들. */
    private String[] fromGangnam(String... extra) {
        var base = new String[] {
            "q", TOKEN, "lng", String.valueOf(GANGNAM_LNG), "lat", String.valueOf(GANGNAM_LAT)
        };
        var all = new String[base.length + extra.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(extra, 0, all, base.length, extra.length);
        return all;
    }

    private static String nameOf(JsonNode place) {
        return place.get("name").asString();
    }

    @Test
    @DisplayName("반경을 주지 않으면 서울 전역에서 찾는다 — 화면 밖 강서구도 나온다")
    void withoutRadiusSearchesWholeCity() throws Exception {
        var found = search(fromGangnam());

        assertThat(found.size()).isEqualTo(2);
        // 가까운 순이다. 전역으로 넓혀도 근처 것이 먼저 나온다.
        assertThat(nameOf(found.get(0))).endsWith("강남점");
        assertThat(nameOf(found.get(1))).endsWith("강서점");
        assertThat(found.get(0).get("distanceM").asDouble()).isLessThan(100);
        assertThat(found.get(1).get("distanceM").asDouble()).isGreaterThan(10_000);
    }

    @Test
    @DisplayName("반경을 주면 그 안으로 좁힌다 — 방 화면의 '이 근처에서' 가 쓰는 길")
    void radiusStillNarrows() throws Exception {
        var found = search(fromGangnam("radius", "2000"));

        assertThat(found.size()).isEqualTo(1);
        assertThat(nameOf(found.get(0))).endsWith("강남점");
    }

    @Test
    @DisplayName("좌표가 없으면 거리 없이 이름순 — 기존 동작 그대로")
    void withoutOriginOrdersByName() throws Exception {
        var found = search("q", TOKEN);

        assertThat(found.size()).isEqualTo(2);
        assertThat(nameOf(found.get(0))).endsWith("강남점");
        assertThat(found.get(0).get("distanceM").isNull()).isTrue();
    }

    @Test
    @DisplayName("폐업은 기본으로 빠진다 — 범위가 넓어져도 그대로다")
    void closedStillExcluded() throws Exception {
        jdbc.update("UPDATE poi SET status = 'CLOSED' WHERE source = 'TEST_SCOPE' AND source_id = '2'");

        var found = search(fromGangnam());
        assertThat(found.size()).isEqualTo(1);

        var all = search(fromGangnam("includeClosed", "true"));
        assertThat(all.size()).isEqualTo(2);
    }
}
