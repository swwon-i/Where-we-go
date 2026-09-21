package com.wherewego.routing;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 경로를 사람이 읽는 단위로 묶은 것. 같은 노선을 연속으로 타면 한 구간이다.
 *
 * <p>정차마다 끊으면 "강남→역삼, 역삼→선릉, 선릉→삼성…" 이 되어 읽을 수가 없다.
 *
 * <h2>기다리는 시간은 따로 한 줄이다</h2>
 *
 * 예전에는 승차 대기와 환승을 뒤따르는 노선 줄에 얹었다. 그러면 "6호선 2정차 10분"처럼
 * 정차 수와 시간이 맞지 않는 줄이 나온다 — 10분 중 4분이 공덕 환승이었다. 이제 탈것 줄은
 * 주행만 담고, 기다리는 일은 {@link #BOARD}·{@link #TRANSFER} 줄로 따로 보인다.
 *
 * @param kind WALK / SUBWAY / BUS / BOARD / TRANSFER
 * @param line 노선 식별자. 버스는 '100100017' 이라 화면에 그대로 쓰면 안 된다.
 *     BOARD·TRANSFER 는 <b>타려는</b> 노선이다
 * @param lineName 표시용 노선 이름. 이름표가 없으면 null
 * @param seconds 이 구간에 걸리는 시간(초). 탈것은 주행만, BOARD 는 승강장까지 가기 + 대기,
 *     TRANSFER 는 환승 통로 + 대기, WALK 는 내린 뒤 승강장에서 올라오는 시간을 포함한다
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
    public static final String SUBWAY = "SUBWAY";
    public static final String BUS = "BUS";
    /** 처음 타는 곳에서 승강장(정류장)까지 가서 기다리는 시간. */
    public static final String BOARD = "BOARD";
    /** 이미 한 번 탄 뒤 다음 노선으로 갈아타는 시간 — 환승 통로(또는 대합실 경유) + 대기. */
    public static final String TRANSFER = "TRANSFER";

    /** 화면에 쓸 이름. 이름표가 없으면 식별자로 떨어진다. */
    @JsonProperty("label")
    public String label() {
        return lineName != null ? lineName : line;
    }

    /** 탈것에 타고 있는 구간인가. 내부 판별용이라 응답에는 넣지 않는다 — {@code kind} 로 충분하다. */
    @JsonIgnore
    public boolean isRide() {
        return SUBWAY.equals(kind) || BUS.equals(kind);
    }

    @JsonIgnore
    public boolean isWalk() {
        return WALK.equals(kind);
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
