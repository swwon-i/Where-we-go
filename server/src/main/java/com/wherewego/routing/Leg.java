package com.wherewego.routing;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 경로를 사람이 읽는 단위로 묶은 것. 같은 노선을 연속으로 타면 한 구간이다.
 *
 * <p>정차마다 끊으면 "강남→역삼, 역삼→선릉, 선릉→삼성…" 이 되어 읽을 수가 없다.
 *
 * @param kind WALK / SUBWAY / BUS
 * @param line 노선 식별자. 버스는 '100100017' 이라 화면에 그대로 쓰면 안 된다
 * @param lineName 표시용 노선 이름. 이름표가 없으면 null
 * @param seconds 이 구간에 걸리는 시간(초). 대기와 환승 도보는 뒤따르는 탈것에 얹혀 있다
 * @param stops 정차 수. 탈것만 의미가 있다
 * @param distanceM 도보 거리(m). 걷는 구간만 채워진다
 * @param toName 이 구간이 끝나는 곳의 이름
 */
public record Leg(
        String kind,
        String line,
        String lineName,
        int seconds,
        int stops,
        double distanceM,
        String toName) {

    public static final String WALK = "WALK";

    /** 화면에 쓸 이름. 이름표가 없으면 식별자로 떨어진다. */
    @JsonProperty("label")
    public String label() {
        return lineName != null ? lineName : line;
    }

    /** 걷는 구간이 아닌가. 내부 판별용이라 응답에는 넣지 않는다 — {@code kind} 로 충분하다. */
    @JsonIgnore
    public boolean isRide() {
        return !WALK.equals(kind);
    }

    Leg plusSeconds(int extra, String newToName) {
        return new Leg(
                kind, line, lineName, seconds + extra, stops, distanceM,
                newToName != null ? newToName : toName);
    }

    Leg merge(Leg next) {
        return new Leg(
                kind,
                line,
                lineName,
                seconds + next.seconds,
                stops + next.stops,
                distanceM + next.distanceM,
                next.toName != null ? next.toName : toName);
    }
}
