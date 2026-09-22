package com.wherewego.routing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * 응답에 나가는 모양.
 *
 * <p>record 의 접근자는 기본으로 전부 직렬화된다. 판별용 메서드를 하나 만들 때마다 API 에
 * 필드가 하나씩 늘어나는 셈이라, 의도한 것만 나가는지 고정해 둔다.
 */
class LegJsonTest {

    // Spring Boot 4 는 Jackson 3 을 쓴다 — databind 가 tools.jackson 으로 옮겨갔다.
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    @DisplayName("탈것 구간은 식별자와 표시 이름을 함께 내보낸다")
    void rideLegShape() throws Exception {
        var leg = new Leg("BUS", "100100017", "120", 600, 11, 0, "수유역");
        var json = mapper.readTree(mapper.writeValueAsString(leg));

        assertThat(json.get("line").asText()).isEqualTo("100100017");
        assertThat(json.get("lineName").asText()).isEqualTo("120");
        assertThat(json.get("label").asText()).isEqualTo("120");
    }

    @Test
    @DisplayName("내부 판별용 isRide 는 응답에 나가지 않는다 — kind 로 충분하다")
    void internalPredicateIsNotExposed() throws Exception {
        var json = mapper.readTree(
                mapper.writeValueAsString(new Leg(Leg.WALK, null, null, 120, 0, 144, "강남")));
        assertThat(json.has("ride")).isFalse();
        assertThat(json.propertyNames())
                .containsExactlyInAnyOrder(
                        "kind", "line", "lineName", "seconds", "stops", "distanceM",
                        "toName", "routeType", "path", "label");
    }

    @Test
    @DisplayName("이름표가 없으면 label 이 식별자로 떨어진다")
    void labelFallsBack() throws Exception {
        var json = mapper.readTree(
                mapper.writeValueAsString(new Leg("BUS", "100100017", null, 600, 3, 0, "수유역")));
        assertThat(json.get("lineName").isNull()).isTrue();
        assertThat(json.get("label").asText()).isEqualTo("100100017");
    }
}
