package com.wherewego.routing;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 경로 한 건.
 *
 * <p>추천하지 않는다 — 순위를 매기거나 "이게 제일 낫다"고 말하지 않는다. 걸리는 시간과
 * 환승 횟수, 노선을 있는 그대로 내보내고 판단은 사용자에게 맡긴다(계획서 §1).
 */
public record Route(int totalSeconds, List<Leg> legs) {

    /**
     * 환승 횟수. 탈것을 갈아탄 횟수이므로 <b>처음 타는 것은 세지 않는다</b>.
     *
     * <p>지하철→버스처럼 수단이 바뀌는 것도 한 번으로 센다. 사용자에게는 똑같이 "갈아타는 일"이다.
     */
    @JsonProperty("transfers")
    public int transfers() {
        long rides = legs.stream().filter(Leg::isRide).count();
        return (int) Math.max(0, rides - 1);
    }

    /** 총 도보 거리(m). */
    @JsonProperty("walkDistanceM")
    public double walkDistanceM() {
        return Math.round(legs.stream().filter(Leg::isWalk).mapToDouble(Leg::distanceM).sum()
                        * 10)
                / 10.0;
    }

    /** 한 줄 요약. 로그와 테스트에서 읽기 위한 것이다. */
    public String summary() {
        String lines = legs.stream()
                .filter(Leg::isRide)
                .map(Leg::label)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.joining(" → "));
        return "%d분 %d초 · 환승 %d · 도보 %.0fm · %s"
                .formatted(
                        totalSeconds / 60,
                        totalSeconds % 60,
                        transfers(),
                        walkDistanceM(),
                        lines.isEmpty() ? "도보" : lines);
    }
}
