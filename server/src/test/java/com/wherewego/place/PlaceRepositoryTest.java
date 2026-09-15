package com.wherewego.place;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 입력 방어 로직. DB 없이 돈다. */
class PlaceRepositoryTest {

    @Test
    @DisplayName("반경이 0 이하면 기본값을 쓴다")
    void radiusDefaultsWhenNonPositive() {
        assertThat(PlaceRepository.clampRadius(0)).isEqualTo(PlaceRepository.DEFAULT_RADIUS_M);
        assertThat(PlaceRepository.clampRadius(-1)).isEqualTo(PlaceRepository.DEFAULT_RADIUS_M);
    }

    @Test
    @DisplayName("반경은 최대치를 넘지 못한다 — 서울 전체를 긁는 질의를 막는다")
    void radiusIsCapped() {
        assertThat(PlaceRepository.clampRadius(999_999)).isEqualTo(PlaceRepository.MAX_RADIUS_M);
    }

    @Test
    @DisplayName("정상 범위 반경은 그대로 통과한다")
    void radiusPassesThrough() {
        assertThat(PlaceRepository.clampRadius(500)).isEqualTo(500);
        assertThat(PlaceRepository.clampRadius(PlaceRepository.MAX_RADIUS_M))
                .isEqualTo(PlaceRepository.MAX_RADIUS_M);
    }

    @Test
    @DisplayName("건수 제한도 기본값과 상한을 갖는다")
    void limitIsClamped() {
        assertThat(PlaceRepository.clampLimit(0)).isEqualTo(50);
        assertThat(PlaceRepository.clampLimit(10)).isEqualTo(10);
        assertThat(PlaceRepository.clampLimit(100_000)).isEqualTo(PlaceRepository.MAX_LIMIT);
    }
}
