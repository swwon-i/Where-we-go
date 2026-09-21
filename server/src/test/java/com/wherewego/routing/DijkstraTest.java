package com.wherewego.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.wherewego.graph.EdgeKind;
import com.wherewego.graph.TestGraphs;
import com.wherewego.graph.TransitGraph;
import com.wherewego.graph.TransitMode;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** 탐색 로직. DB 없이 합성 그래프로 돈다. */
class DijkstraTest {

    private static TransitGraph chain(int length) {
        var b = TestGraphs.builder();
        for (int i = 1; i <= length; i++) b.walk(i);
        for (int i = 1; i < length; i++) b.walkEdge(i, i + 1, 1, 1.2);
        return b.build();
    }

    private static int[] hours(int base, int peakHour, int peakValue) {
        var h = new int[24];
        Arrays.fill(h, base);
        h[peakHour] = peakValue;
        return h;
    }

    @Nested
    class Search {

        @Test
        @DisplayName("빨리 가면 멀리 돌아도 그쪽이다 — 최소 홉이 아니라 최소 시간")
        void shortestNotFewestHops() {
            var g = TestGraphs.builder()
                    .walk(1).walk(2).walk(3)
                    .walkEdge(1, 2, 100, 120)
                    .walkEdge(1, 3, 10, 12)
                    .walkEdge(3, 2, 10, 12)
                    .build();
            var r = Dijkstra.run(g, g.indexOf(1), new int[] {g.indexOf(2)}, Dijkstra.NO_HOUR);
            assertThat(r.dist()[g.indexOf(2)]).isEqualTo(20);
        }

        @Test
        @DisplayName("닿지 못하는 노드는 UNREACHED 로 남는다")
        void unreachableStaysUnreached() {
            var g = TestGraphs.builder().walk(1).walk(2).walk(9).walkEdge(1, 2, 10, 12).build();
            var r = Dijkstra.run(g, g.indexOf(1), new int[] {g.indexOf(9)}, Dijkstra.NO_HOUR);
            assertThat(r.reached(g.indexOf(9))).isFalse();
        }

        @Test
        @DisplayName("목표를 다 찾으면 멈춘다 — 서울 전체를 훑지 않기 위한 장치")
        void stopsOnceTargetsAreSettled() {
            var g = chain(100);
            int near = Dijkstra.run(g, g.indexOf(1), new int[] {g.indexOf(3)}, Dijkstra.NO_HOUR)
                    .settled();
            int far = Dijkstra.run(g, g.indexOf(1), new int[] {g.indexOf(99)}, Dijkstra.NO_HOUR)
                    .settled();
            assertThat(near).isLessThan(far);
        }

        @Test
        @DisplayName("목표가 늘어도 탐색은 한 번이다 — one-to-many 의 근거")
        void manyTargetsCostNoMoreThanTheFarthest() {
            var g = chain(50);
            int onlyFar = Dijkstra.run(g, g.indexOf(1), new int[] {g.indexOf(49)}, Dijkstra.NO_HOUR)
                    .settled();
            int many = Dijkstra.run(
                            g,
                            g.indexOf(1),
                            new int[] {
                                g.indexOf(5), g.indexOf(15), g.indexOf(25),
                                g.indexOf(35), g.indexOf(49)
                            },
                            Dijkstra.NO_HOUR)
                    .settled();
            assertThat(many).isEqualTo(onlyFar);
        }
    }

    @Nested
    class DepartureHour {

        @Test
        @DisplayName("시간대가 주어지면 배열에서 고른다")
        void picksFromHourlyArray() {
            var g = TestGraphs.builder()
                    .platform(1, TransitMode.SUBWAY, "2", "A")
                    .platform(2, TransitMode.SUBWAY, "2", "B")
                    .hourlyEdge(1, 2, 10, hours(10, 19, 500), EdgeKind.RIDE, "2")
                    .build();
            assertThat(Dijkstra.run(g, g.indexOf(1), new int[] {g.indexOf(2)}, 8)
                            .dist()[g.indexOf(2)])
                    .isEqualTo(10);
            assertThat(Dijkstra.run(g, g.indexOf(1), new int[] {g.indexOf(2)}, 19)
                            .dist()[g.indexOf(2)])
                    .isEqualTo(500);
        }

        @Test
        @DisplayName("시간대를 안 주면 기본 가중치를 쓴다")
        void noHourUsesBaseWeight() {
            var g = TestGraphs.builder()
                    .platform(1, TransitMode.SUBWAY, "2", "A")
                    .platform(2, TransitMode.SUBWAY, "2", "B")
                    .hourlyEdge(1, 2, 42, hours(999, 0, 999), EdgeKind.RIDE, "2")
                    .build();
            var r = Dijkstra.run(g, g.indexOf(1), new int[] {g.indexOf(2)}, Dijkstra.NO_HOUR);
            assertThat(r.dist()[g.indexOf(2)]).isEqualTo(42);
        }

        @Test
        @DisplayName("시간대가 바뀌면 고르는 경로 자체가 바뀐다 — 시간대 비교 기능의 근거")
        void hourChangesTheChosenPath() {
            var g = TestGraphs.builder()
                    .platform(1, TransitMode.SUBWAY, "A", "출발")
                    .platform(2, TransitMode.SUBWAY, "A", "도착")
                    .platform(3, TransitMode.SUBWAY, "B", "우회")
                    .hourlyEdge(1, 2, 50, hours(50, 19, 900), EdgeKind.RIDE, "A")
                    .edge(1, 3, 100, EdgeKind.RIDE, "B")
                    .edge(3, 2, 100, EdgeKind.RIDE, "B")
                    .build();
            assertThat(Dijkstra.run(g, g.indexOf(1), new int[] {g.indexOf(2)}, 8)
                            .dist()[g.indexOf(2)])
                    .isEqualTo(50);   // A 직통
            assertThat(Dijkstra.run(g, g.indexOf(1), new int[] {g.indexOf(2)}, 19)
                            .dist()[g.indexOf(2)])
                    .isEqualTo(200);  // B 우회
        }

        @Test
        @DisplayName("운행 없는 시간대의 엣지는 건너뛴다 — 낮에 N버스를 태우지 않는다")
        void noServiceHourIsSkipped() {
            // 새벽 2시에만 다니는 직통(10초)과 늘 다니는 우회(200초).
            var nightOnly = new int[24];
            Arrays.fill(nightOnly, TransitGraph.NO_SERVICE);
            nightOnly[2] = 10;
            var g = TestGraphs.builder()
                    .platform(1, TransitMode.BUS, "N26", "출발")
                    .platform(2, TransitMode.BUS, "N26", "도착")
                    .platform(3, TransitMode.BUS, "간선", "우회")
                    .hourlyEdge(1, 2, 10, nightOnly, EdgeKind.RIDE, "N26")
                    .edge(1, 3, 100, EdgeKind.RIDE, "간선")
                    .edge(3, 2, 100, EdgeKind.RIDE, "간선")
                    .build();
            assertThat(Dijkstra.run(g, g.indexOf(1), new int[] {g.indexOf(2)}, 2)
                            .dist()[g.indexOf(2)])
                    .isEqualTo(10);   // 새벽 2시에는 N버스
            assertThat(Dijkstra.run(g, g.indexOf(1), new int[] {g.indexOf(2)}, 19)
                            .dist()[g.indexOf(2)])
                    .isEqualTo(200);  // 19시에는 다니지 않으므로 우회
        }

        @Test
        @DisplayName("운행 없음은 공짜가 아니다 — 그 엣지뿐이면 도달 불가")
        void noServiceIsNotFree() {
            var closed = new int[24];
            Arrays.fill(closed, TransitGraph.NO_SERVICE);
            closed[8] = 60;
            var g = TestGraphs.builder()
                    .platform(1, TransitMode.SUBWAY, "2", "A")
                    .platform(2, TransitMode.SUBWAY, "2", "B")
                    .hourlyEdge(1, 2, 60, closed, EdgeKind.RIDE, "2")
                    .build();
            var r = Dijkstra.run(g, g.indexOf(1), new int[] {g.indexOf(2)}, 3);
            assertThat(r.reached(g.indexOf(2))).isFalse();
        }
    }
}
