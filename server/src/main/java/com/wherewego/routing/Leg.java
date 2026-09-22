package com.wherewego.routing;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

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
 * @param routeType 버스 노선 유형(간선·지선·광역·마을·순환…). 지도의 선 색을 고른다. 지하철·도보는 null
 * @param path 지도에 그릴 좌표열 {@code [[경도, 위도], …]}(WGS84). 도보는 보행망 교차점을 따라가 실제
 *     길 모양에 가깝고, 지하철·버스는 지나는 역·정류장을 잇는 직선이다 — 선로·도로 모양은 데이터에
 *     없다. 승차·환승은 그 자리 점 하나다. 앞 구간의 끝점과 다음 구간의 첫 점은 같은 곳이다
 */
public record Leg(
        String kind,
        String line,
        String lineName,
        int seconds,
        int stops,
        double distanceM,
        String toName,
        String routeType,
        List<double[]> path) {

    /** 좌표 없이 만든다. 시간·노선만 보는 테스트와 조립 도중에 쓴다. */
    public Leg(String kind, String line, String lineName, int seconds, int stops, double distanceM,
            String toName) {
        this(kind, line, lineName, seconds, stops, distanceM, toName, null, List.of());
    }

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
                newToName != null ? newToName : toName, routeType, path);
    }

    Leg merge(Leg next) {
        return new Leg(
                kind,
                line,
                lineName,
                seconds + next.seconds,
                stops + next.stops,
                distanceM + next.distanceM,
                next.toName != null ? next.toName : toName,
                routeType != null ? routeType : next.routeType,
                joinPaths(path, next.path));
    }

    /** 두 좌표열을 잇는다. 앞의 끝점과 뒤의 첫 점이 같으면 한 번만 넣는다. */
    static List<double[]> joinPaths(List<double[]> a, List<double[]> b) {
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        var out = new java.util.ArrayList<double[]>(a.size() + b.size());
        out.addAll(a);
        int from = java.util.Arrays.equals(a.getLast(), b.getFirst()) ? 1 : 0;
        out.addAll(b.subList(from, b.size()));
        return List.copyOf(out);
    }
}
