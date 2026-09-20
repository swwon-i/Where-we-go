package com.wherewego.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.wherewego.graph.EdgeKind;
import com.wherewego.graph.TestGraphs;
import com.wherewego.graph.TransitGraph;
import com.wherewego.graph.TransitMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** 엣지의 나열을 사람이 읽는 구간으로 묶는 부분. */
class RouteBuilderTest {

    private static Route route(TransitGraph g, long from, long to) {
        int src = g.indexOf(from);
        int dst = g.indexOf(to);
        var result = Dijkstra.run(g, src, new int[] {dst}, Dijkstra.NO_HOUR);
        return RouteBuilder.build(g, result, dst);
    }

    /** 강남(10) ─2호선─ 역삼(12) ─2호선─ 선릉(13) → 선릉 정류장(14) */
    private static TransitGraph line2() {
        return TestGraphs.builder()
                .route(TransitMode.SUBWAY, "2", "2호선")
                .stop(10, TransitMode.SUBWAY, "강남")
                .platform(11, TransitMode.SUBWAY, "2", "강남")
                .platform(12, TransitMode.SUBWAY, "2", "역삼")
                .platform(13, TransitMode.SUBWAY, "2", "선릉")
                .stop(14, TransitMode.SUBWAY, "선릉")
                .edge(10, 11, 90, EdgeKind.BOARD, "2")
                .edge(11, 12, 100, EdgeKind.RIDE, "2")
                .edge(12, 13, 110, EdgeKind.RIDE, "2")
                .edge(13, 14, 44, EdgeKind.ALIGHT, "2")
                .build();
    }

    @Nested
    class Grouping {

        @Test
        @DisplayName("같은 노선을 연달아 타면 한 구간으로 묶인다 — 정차마다 끊으면 못 읽는다")
        void consecutiveRidesMerge() {
            var route = route(line2(), 10, 14);
            var rides = route.legs().stream().filter(Leg::isRide).toList();
            assertThat(rides).hasSize(1);
            assertThat(rides.getFirst().stops()).isEqualTo(2);
            assertThat(route.totalSeconds()).isEqualTo(344);
        }

        @Test
        @DisplayName("구간 시간의 합이 총 소요시간과 같다")
        void legSecondsSumToTotal() {
            var route = route(line2(), 10, 14);
            int sum = route.legs().stream().mapToInt(Leg::seconds).sum();
            assertThat(sum).isEqualTo(route.totalSeconds());
        }

        @Test
        @DisplayName("걷는 구간은 실제 거리를 더한다 — 시간에 속도를 곱해 되짚지 않는다")
        void walkDistanceComesFromTheEdge() {
            var g = TestGraphs.builder()
                    .walk(1).walk(2).walk(3)
                    .walkEdge(1, 2, 60, 72.5)
                    .walkEdge(2, 3, 60, 71.5)
                    .build();
            var route = route(g, 1, 3);
            assertThat(route.legs()).hasSize(1);
            assertThat(route.walkDistanceM()).isEqualTo(144.0);
            assertThat(route.transfers()).isZero();
        }
    }

    @Nested
    class Transfers {

        @Test
        @DisplayName("처음 타는 것은 환승이 아니다")
        void firstBoardingIsNotATransfer() {
            var g = TestGraphs.builder()
                    .route(TransitMode.SUBWAY, "2", "2호선")
                    .route(TransitMode.SUBWAY, "3", "3호선")
                    .stop(10, TransitMode.SUBWAY, "강남")
                    .platform(11, TransitMode.SUBWAY, "2", "강남")
                    .platform(12, TransitMode.SUBWAY, "2", "교대")
                    .platform(20, TransitMode.SUBWAY, "3", "교대")
                    .platform(21, TransitMode.SUBWAY, "3", "고속터미널")
                    .edge(10, 11, 90, EdgeKind.BOARD, "2")
                    .edge(11, 12, 100, EdgeKind.RIDE, "2")
                    .edge(12, 20, 150, EdgeKind.TRANSFER, "3")
                    .edge(20, 21, 120, EdgeKind.RIDE, "3")
                    .build();
            var route = route(g, 10, 21);
            assertThat(route.transfers()).isEqualTo(1);
            assertThat(route.summary()).contains("2호선 → 3호선");
        }

        @Test
        @DisplayName("지하철과 버스는 다른 구간이고, 갈아타면 환승 1이다")
        void subwayAndBusAreDistinctLegs() {
            var g = TestGraphs.builder()
                    .route(TransitMode.SUBWAY, "2", "2호선")
                    .route(TransitMode.BUS, "100100017", "120")
                    .stop(10, TransitMode.SUBWAY, "강남")
                    .platform(11, TransitMode.SUBWAY, "2", "강남")
                    .platform(12, TransitMode.SUBWAY, "2", "역삼")
                    .stop(14, TransitMode.SUBWAY, "역삼")
                    .stop(30, TransitMode.BUS, "역삼역")
                    .platform(31, TransitMode.BUS, "100100017", "역삼역")
                    .platform(32, TransitMode.BUS, "100100017", "수유역")
                    .stop(33, TransitMode.BUS, "수유역")
                    .edge(10, 11, 90, EdgeKind.BOARD, "2")
                    .edge(11, 12, 100, EdgeKind.RIDE, "2")
                    .edge(12, 14, 44, EdgeKind.ALIGHT, "2")
                    .walkEdge(14, 30, 120, 144)
                    .edge(30, 31, 200, EdgeKind.BOARD, "100100017")
                    .edge(31, 32, 180, EdgeKind.RIDE, "100100017")
                    .edge(32, 33, 1, EdgeKind.ALIGHT, "100100017")
                    .build();
            var route = route(g, 10, 33);
            assertThat(route.legs().stream().map(Leg::kind))
                    .containsExactly("SUBWAY", "WALK", "BUS");
            assertThat(route.transfers()).isEqualTo(1);
        }
    }

    @Nested
    class RouteNames {

        /** 버스 line 은 '100100017' 같은 식별자라 그대로 보여주면 읽을 수 없다. */
        private TransitGraph busGraph(boolean withName) {
            var b = TestGraphs.builder();
            if (withName) b.route(TransitMode.BUS, "100100017", "120");
            return b.stop(30, TransitMode.BUS, "북한산")
                    .platform(31, TransitMode.BUS, "100100017", "북한산")
                    .platform(32, TransitMode.BUS, "100100017", "수유역")
                    .stop(33, TransitMode.BUS, "수유역")
                    .edge(30, 31, 200, EdgeKind.BOARD, "100100017")
                    .edge(31, 32, 600, EdgeKind.RIDE, "100100017")
                    .edge(32, 33, 1, EdgeKind.ALIGHT, "100100017")
                    .build();
        }

        @Test
        @DisplayName("표시 이름이 요약에서 식별자를 대신한다")
        void displayNameReplacesId() {
            var route = route(busGraph(true), 30, 33);
            assertThat(route.summary()).contains("120").doesNotContain("100100017");
        }

        @Test
        @DisplayName("이름이 붙어도 식별자는 그대로다 — 노선을 가르는 키이기 때문")
        void identityIsPreserved() {
            var leg = route(busGraph(true), 30, 33).legs().stream()
                    .filter(Leg::isRide)
                    .findFirst()
                    .orElseThrow();
            assertThat(leg.line()).isEqualTo("100100017");
            assertThat(leg.lineName()).isEqualTo("120");
        }

        @Test
        @DisplayName("이름표가 없어도 경로는 나온다 — 표시만 식별자로 떨어진다")
        void fallsBackToIdentifier() {
            var leg = route(busGraph(false), 30, 33).legs().stream()
                    .filter(Leg::isRide)
                    .findFirst()
                    .orElseThrow();
            assertThat(leg.label()).isEqualTo("100100017");
        }
    }

    @Test
    @DisplayName("닿지 못하면 null")
    void unreachableIsNull() {
        var g = TestGraphs.builder().walk(1).walk(2).walk(9).walkEdge(1, 2, 10, 12).build();
        assertThat(route(g, 1, 9)).isNull();
    }
}
