package com.wherewego.graph;

/**
 * 엣지의 종류. 값 자체는 {@code graph_edge.kind} 와 같다.
 *
 * <p>탐색은 종류를 보지 않는다 — 모든 규칙(대기, 환승 도보, 진출입)이 이미 가중치에 들어가 있다.
 * 종류가 필요한 곳은 <b>결과를 사람이 읽는 형태로 묶을 때</b>뿐이다.
 *
 * <p>DB 에서는 텍스트지만 메모리에서는 {@code byte} 로 둔다. 엣지가 60만 개라 한 칸당
 * 8바이트(참조)와 1바이트의 차이가 그대로 수십 MB 차이가 된다.
 */
public enum EdgeKind {
    /** 보행 ↔ 보행. */
    WALK,
    /** 보행 ↔ 정류장. 진출입 도보. */
    ACCESS,
    /** 정류장 → 플랫폼. 대기 = 배차 ÷ 2. */
    BOARD,
    /** 플랫폼 → 정류장. */
    ALIGHT,
    /** 플랫폼 → 플랫폼. 역간 소요시간. */
    RIDE,
    /** 플랫폼 ↔ 플랫폼. 환승 도보 + 대기. */
    TRANSFER;

    private static final EdgeKind[] VALUES = values();

    public static EdgeKind of(byte code) {
        return VALUES[code];
    }

    public byte code() {
        return (byte) ordinal();
    }

    /** 노선이 바뀌는 지점인가. 환승 횟수를 셀 때 쓴다. */
    public boolean isBoarding() {
        return this == BOARD || this == TRANSFER;
    }

    /** 걷는 엣지인가. */
    public boolean isWalking() {
        return this == WALK || this == ACCESS;
    }
}
