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
                    .containsExactly("BOARD", "SUBWAY", "WALK", "TRANSFER", "BUS");
            assertThat(route.transfers()).isEqualTo(1);
        }

        @Test
        @DisplayName("환승은 따로 한 줄이고, 탈것 줄은 주행만 담는다 — '2정차 10분' 이 안 나온다")
        void transferIsItsOwnLeg() {
            var g = TestGraphs.builder()
                    .route(TransitMode.SUBWAY, "5", "5호선")
                    .route(TransitMode.SUBWAY, "6", "6호선")
                    .stop(10, TransitMode.SUBWAY, "화곡")
                    .platform(11, TransitMode.SUBWAY, "5", "화곡")
                    .platform(12, TransitMode.SUBWAY, "5", "공덕")
                    .platform(13, TransitMode.SUBWAY, "6", "공덕")
                    .platform(14, TransitMode.SUBWAY, "6", "삼각지")
                    .stop(15, TransitMode.SUBWAY, "삼각지")
                    .walk(16)
                    .edge(10, 11, 202, EdgeKind.BOARD, "5")
                    .edge(11, 12, 1440, EdgeKind.RIDE, "5")
                    .edge(12, 13, 241, EdgeKind.TRANSFER, "6")
                    .edge(13, 14, 240, EdgeKind.RIDE, "6")
                    .edge(14, 15, 64, EdgeKind.ALIGHT, "6")
                    .walkEdge(15, 16, 487, 584)
                    .build();
            var legs = route(g, 10, 16).legs();

            assertThat(legs.stream().map(Leg::kind))
                    .containsExactly("BOARD", "SUBWAY", "TRANSFER", "SUBWAY", "WALK");
            assertThat(legs.get(2).label()).isEqualTo("6호선");   // 타려는 노선
            assertThat(legs.get(2).seconds()).isEqualTo(241);
            assertThat(legs.get(3).seconds()).isEqualTo(240);     // 주행만
            assertThat(legs.get(4).seconds()).isEqualTo(64 + 487); // 올라오기는 도보에
            assertThat(route(g, 10, 16).transfers()).isEqualTo(1);
        }

        @Test
        @DisplayName("구간마다 지도에 그릴 좌표열이 붙는다 — 도보는 교차점을, 탈것은 정거장을 잇는다")
        void legsCarryPaths() {
            var g = TestGraphs.builder()
                    .route(TransitMode.SUBWAY, "5", "5호선")
                    .walk(1).walk(2)
                    .stop(10, TransitMode.SUBWAY, "화곡")
                    .platform(11, TransitMode.SUBWAY, "5", "화곡")
                    .platform(12, TransitMode.SUBWAY, "5", "까치산")
                    .platform(13, TransitMode.SUBWAY, "5", "신정")
                    .walkEdge(1, 2, 30, 36)
                    .walkEdge(2, 10, 30, 36)
                    .edge(10, 11, 200, EdgeKind.BOARD, "5")
                    .edge(11, 12, 120, EdgeKind.RIDE, "5")
                    .edge(12, 13, 120, EdgeKind.RIDE, "5")
                    .build();
            var legs = route(g, 1, 13).legs();

            // 좌표는 따로 주지 않으면 (노드 번호, 0) 이다
            assertThat(lngs(legs.get(0))).containsExactly(1.0, 2.0, 10.0);   // 도보: 이은 곳이 한 번씩만
            assertThat(lngs(legs.get(1))).containsExactly(11.0);             // 승차: 점 하나
            assertThat(lngs(legs.get(2))).containsExactly(11.0, 12.0, 13.0); // 5호선: 정거장마다
        }

        @Test
        @DisplayName("버스 구간은 노선 유형을 싣는다 — 지도가 간선·지선 색을 고른다")
        void busLegCarriesRouteType() {
            var g = TestGraphs.builder()
                    .route(TransitMode.BUS, "100100017", "120", "간선")
                    .stop(30, TransitMode.BUS, "역삼역")
                    .platform(31, TransitMode.BUS, "100100017", "역삼역")
                    .platform(32, TransitMode.BUS, "100100017", "수유역")
                    .edge(30, 31, 200, EdgeKind.BOARD, "100100017")
                    .edge(31, 32, 180, EdgeKind.RIDE, "100100017")
                    .build();
            var ride = route(g, 30, 32).legs().stream().filter(Leg::isRide).findFirst().orElseThrow();
            assertThat(ride.routeType()).isEqualTo("간선");
        }

        @Test
        @DisplayName("대합실로 나갔다 다시 타도 환승으로 보인다 — 공덕 5→6호선은 이쪽이 1초 쌌다")
        void reboardingViaConcourseIsATransfer() {
            var g = TestGraphs.builder()
                    .route(TransitMode.SUBWAY, "5", "5호선")
                    .route(TransitMode.SUBWAY, "6", "6호선")
                    .stop(10, TransitMode.SUBWAY, "화곡")
                    .platform(11, TransitMode.SUBWAY, "5", "화곡")
                    .platform(12, TransitMode.SUBWAY, "5", "공덕")
                    .stop(13, TransitMode.SUBWAY, "공덕")
                    .platform(14, TransitMode.SUBWAY, "6", "공덕")
                    .platform(15, TransitMode.SUBWAY, "6", "삼각지")
                    .edge(10, 11, 202, EdgeKind.BOARD, "5")
                    .edge(11, 12, 1350, EdgeKind.RIDE, "5")
                    .edge(12, 13, 30, EdgeKind.ALIGHT, "5")
                    .edge(13, 14, 210, EdgeKind.BOARD, "6")
                    .edge(14, 15, 240, EdgeKind.RIDE, "6")
                    .build();
            var legs = route(g, 10, 15).legs();
            assertThat(legs.stream().map(Leg::kind))
                    .containsExactly("BOARD", "SUBWAY", "TRANSFER", "SUBWAY");
            assertThat(legs.get(2).seconds()).isEqualTo(30 + 210);  // 올라오기 + 다시 타기
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

    private static java.util.List<Double> lngs(Leg leg) {
        return leg.path().stream().map(p -> p[0]).toList();
    }
}
