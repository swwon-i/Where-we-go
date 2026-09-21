package com.wherewego.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.wherewego.graph.GraphCache;
import com.wherewego.graph.TransitGraph;
import com.wherewego.graph.TransitMode;
import java.net.InetSocketAddress;
import java.net.Socket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 빌드된 실제 그래프로 도는 검증.
 *
 * <p>DB 가 없으면 통째로 건너뛴다 — 나머지 테스트는 원본 없이도 돌아야 하고 이것만 환경에 기댄다.
 *
 * <pre>
 *   docker compose up -d db
 *   python -m etl.build_graph
 *   ./gradlew test
 * </pre>
 *
 * <p>여기서만 깨지면 데이터나 빌드를 의심하고, {@code DijkstraTest} 까지 같이 깨지면 로직을
 * 의심한다. 둘을 나눈 이유다.
 */
@SpringBootTest
@EnabledIf("databaseIsUp")
class RouteIntegrationTest {

    static boolean databaseIsUp() {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 5432), 300);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Autowired GraphCache cache;
    @Autowired RouteService service;

    private TransitGraph graph;

    @BeforeAll
    static void announce() {
        // 건너뛰지 않고 여기까지 왔다는 것은 DB 가 떠 있다는 뜻이다.
    }

    private TransitGraph graph() {
        if (graph == null) {
            try {
                graph = cache.get();
            } catch (IllegalStateException e) {
                assumeTrue(false, "활성 그래프 빌드가 없다: " + e.getMessage());
            }
        }
        return graph;
    }

    private Route between(String from, String to, int hour) {
        var g = graph();
        return service.route(
                g,
                service.atStop(g, TransitMode.SUBWAY, from).node(),
                service.atStop(g, TransitMode.SUBWAY, to).node(),
                hour);
    }

    /**
     * 고르는 기준은 <b>직결이 실제로 최선인 구간</b>이어야 한다. 한 노선으로 이어진다는 것만으로는
     * 부족하다 — 강남→홍대입구는 2호선으로 이어지지만 순환선 반바퀴(17정차·실측 38.5분)라
     * 9호선 급행으로 가로지르는 편이 실측 기준 5.6분 빠르다. 그 구간을 "직결이어야 한다"고
     * 묶어 두면 그래프가 옳아질수록 깨지는 테스트가 된다.
     */
    @ParameterizedTest(name = "{0} → {1} 은 {2} 한 노선으로, {3}분 안")
    @CsvSource({"화곡,종로3가,5호선,45", "노원,사당,4호선,60", "건대입구,왕십리,2호선,25"})
    @DisplayName("직결 구간에 불필요한 환승을 만들지 않는다")
    void singleLineHasNoTransfer(String from, String to, String line, int maxMinutes) {
        var route = between(from, to, Dijkstra.NO_HOUR);
        assertThat(route).as("%s → %s 도달 불가", from, to).isNotNull();
        assertThat(route.transfers()).isZero();
        assertThat(route.legs().stream().filter(Leg::isRide).map(Leg::label))
                .containsExactly(line);
        assertThat(route.totalSeconds()).isLessThanOrEqualTo(maxMinutes * 60);
    }

    @ParameterizedTest(name = "{0} → {1} 은 갈아타야 한다")
    @CsvSource({"잠실,광화문", "강남,광화문", "수유,여의도"})
    @DisplayName("환승이 필요한 구간에서는 실제로 노선이 바뀐다")
    void transferHappens(String from, String to) {
        var route = between(from, to, Dijkstra.NO_HOUR);
        assertThat(route).isNotNull();
        assertThat(route.transfers()).isGreaterThanOrEqualTo(1);
        assertThat(route.legs().stream().filter(Leg::isRide).map(Leg::label).distinct().count())
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("환승은 공짜가 아니다 — 실측 환승 도보가 빠지면 1초에 갈아타진다")
    void transferIsNotFree() {
        var route = between("잠실", "광화문", Dijkstra.NO_HOUR);
        var second = route.legs().stream().filter(Leg::isRide).toList().get(1);
        // 환승 도보 + 대기가 함께 붙으므로 대기만 있을 때보다 확실히 크다
        assertThat(second.seconds()).isGreaterThan(120);
    }

    @Test
    @DisplayName("시간대를 바꾸면 소요시간이 달라진다 — departureHour 가 무의미하지 않다")
    void departureHourChangesSomething() {
        var pairs = new String[][] {{"잠실", "광화문"}, {"강남", "시청"}, {"노원", "사당"}};
        int maxDiff = 0;
        for (var pair : pairs) {
            var morning = between(pair[0], pair[1], 8);
            var evening = between(pair[0], pair[1], 22);
            if (morning != null && evening != null) {
                maxDiff = Math.max(
                        maxDiff, Math.abs(morning.totalSeconds() - evening.totalSeconds()));
            }
        }
        assertThat(maxDiff).as("시간대를 바꿔도 같다면 배열 가중치를 의심할 것").isPositive();
    }

    @Test
    @DisplayName("좌표를 보행망에 붙이고 그 사이 경로가 나온다")
    void snapsCoordinatesAndRoutes() {
        var g = graph();
        var from = service.atCoordinate(g, 127.0276, 37.4979);   // 강남역
        var to = service.atCoordinate(g, 126.9769, 37.5759);     // 종로 방면
        assertThat(from.snapDistanceM()).isLessThan(200);
        assertThat(to.snapDistanceM()).isLessThan(200);

        var route = service.route(g, from.node(), to.node(), Dijkstra.NO_HOUR);
        assertThat(route).isNotNull();
        assertThat(route.walkDistanceM()).isPositive();
    }

    @Test
    @DisplayName("버스가 그래프에 들어가 있고 노선 이름이 붙는다")
    void busIsPresentWithNames() {
        var g = graph();
        int bus = g.findStop(TransitMode.BUS, "수유역.강북구청");
        assertThat(bus).as("버스 정류장을 찾지 못했다").isNotNegative();
    }

    @Test
    @DisplayName("없는 역은 예외")
    void unknownStopThrows() {
        var g = graph();
        assertThat(g.findStop(TransitMode.SUBWAY, "없는역")).isEqualTo(-1);
    }

    @Test
    @DisplayName("상태에 빌드 번호와 규모가 보인다")
    void statusReportsBuild() {
        graph();
        var status = cache.status();
        assertThat(status.loaded()).isTrue();
        assertThat(status.nodes()).isGreaterThan(100_000);
        assertThat(status.edges()).isGreaterThan(100_000);
        assertThat(status.stale()).isFalse();
    }
}
