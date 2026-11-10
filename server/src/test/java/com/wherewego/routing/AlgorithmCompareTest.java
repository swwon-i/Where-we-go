package com.wherewego.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.wherewego.graph.GraphLoader;
import com.wherewego.graph.TransitGraph;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * 탐색 알고리즘 대조. "다익스트라 말고 A* 는 왜 안 쓰나"에 숫자로 답하기 위한 측정이다.
 *
 * <p>세 가지를 나란히 돌린다. <b>셋 다 같은 답을 낸다</b> — 그걸 먼저 확인하고 시간을 재므로
 * 빠른 쪽이 덜 정확한 게 아니다.
 *
 * <ol>
 *   <li><b>다익스트라 1회 (one-to-many)</b> — 지금 쓰는 것. 후보 전부를 확정하면 멈춘다
 *   <li><b>다익스트라 N회 (1:1)</b> — 후보마다 따로 탐색. 조기 종료가 후보 하나에만 걸린다
 *   <li><b>A* N회 (1:1)</b> — 같은 1:1 인데 직선거리 휴리스틱으로 목표 쪽으로 치우친다
 * </ol>
 *
 * <p>A* 는 1:1 에서만 뜻이 있다. 목표가 여러 곳이면 치우칠 방향이 하나로 정해지지 않으므로
 * 목표마다 따로 돌려야 하고, 그게 2·3번이 1번과 비교되는 이유다. 그래서 측정은
 * <b>"A* 가 1:1 에서 다익스트라를 이기는가"</b>와 <b>"그 이득이 N 번 돌리는 손해를 메우는가"</b>를
 * 따로 본다.
 *
 * <h2>휴리스틱</h2>
 *
 * {@code h(n) = 직선거리(n, 목표) / 그래프에서 관측되는 최고 속도}. 허용(admissible)임의 증명은
 * {@link BenchSupport#maxSpeedMps}에 적었다. 분모가 <b>급행 지하철 속도</b>라 대부분의 이동
 * (도보·버스)에 대해 휴리스틱이 실제 비용보다 한참 작게 나오는데, 그 느슨함이 곧 A* 의 성능이다.
 * 보고서에 {@code h / 실제} 비율을 같이 적는다 — 1 에 가까울수록 좋은 휴리스틱이다.
 *
 * <p>결과는 {@code build/reports/perf/algorithms.md}. {@code ./gradlew perfTest} 로 돈다.
 */
@Tag("perf")
@SpringBootTest
@Transactional
@EnabledIf("databaseIsUp")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AlgorithmCompareTest {

    static boolean databaseIsUp() {
        return BenchSupport.databaseIsUp();
    }

    private static final int HOUR = 19;
    private static final long SEED = 42;

    /** 후보 수. 행렬의 실제 모양(10명 × 20곳)에서 가져왔다. */
    private static final int TARGETS = 20;

    /** N회 돌리는 쪽은 한 판에 탐색이 TARGETS 번이라 회차를 줄인다. */
    private static final int WARMUP = 5;
    private static final int RUNS = 30;

    private static final double CLUSTER_M = 1_000;

    @Autowired GraphLoader loader;

    private final BenchSupport.Report report = new BenchSupport.Report();

    private TransitGraph graph;
    private int[] sources;
    private int[] scattered;   // 서울 전역에 흩어진 후보
    private int[] clustered;   // 한 동네에 모인 후보 — 실제 방의 모양
    private double maxSpeed;

    @BeforeAll
    void setUp() {
        graph = loader.load();
        int[] walk = BenchSupport.connectedWalkNodes(graph, HOUR);
        var rng = new Random(SEED);

        sources = BenchSupport.pick(walk, WARMUP + RUNS, rng);
        scattered = BenchSupport.pick(walk, TARGETS, rng);
        int center = walk[rng.nextInt(walk.length)];
        clustered = BenchSupport.pickNear(graph, walk, center, CLUSTER_M, TARGETS, rng);

        maxSpeed = BenchSupport.maxSpeedMps(graph, HOUR);

        report.header("탐색 알고리즘 대조 — 다익스트라 대 A*",
                "시간대 %d시 · 씨앗 %d · 후보 %d곳 · 예열 %d회 · 측정 %d회"
                        .formatted(HOUR, SEED, TARGETS, WARMUP, RUNS));
        report.line("빌드 #%d · 노드 %,d · 엣지 %,d · 관측 최고 속도 %.1f m/s (%.0f km/h)",
                graph.buildId(), graph.nodeCount(), graph.edgeCount(), maxSpeed, maxSpeed * 3.6);
        report.line("");
    }

    @AfterAll
    void write() throws IOException {
        report.write("algorithms.md");
    }

    // ── 0. 같은 답을 내는지 ──────────────────────────────────────────────────

    /** 시간을 재기 전에 할 일. 다른 답을 내는 두 알고리즘의 속도를 비교하면 의미가 없다. */
    @Test
    @Order(1)
    void sameAnswers() {
        int checked = 5;
        for (int i = 0; i < checked; i++) {
            int s = sources[i];
            var oneToMany = Dijkstra.run(graph, s, scattered, HOUR);
            for (int t : scattered) {
                int viaAstar = astar(graph, s, t, HOUR, maxSpeed).dist();
                int viaOneToOne = Dijkstra.run(graph, s, new int[] {t}, HOUR).dist()[t];
                assertThat(viaAstar)
                        .as("A* 와 다익스트라의 답이 다르다: %d → %d", s, t)
                        .isEqualTo(oneToMany.dist()[t]);
                assertThat(viaOneToOne).isEqualTo(oneToMany.dist()[t]);
            }
        }
        report.line("## 0. 먼저 — 세 방식이 같은 답을 내는가");
        report.line("");
        report.line("출발지 %d곳 × 후보 %d곳 = %d 쌍에서 세 방식의 소요시간이 모두 같다. "
                + "아래 시간은 **같은 답을 내는 세 방식**의 비교다.", checked, TARGETS, checked * TARGETS);
        report.line("");
    }

    // ── 1. 휴리스틱이 얼마나 쓸모 있나 ───────────────────────────────────────

    @Test
    @Order(2)
    void heuristicQuality() {
        var ratios = new long[RUNS * TARGETS];
        int at = 0;
        for (int r = 0; r < RUNS; r++) {
            int s = sources[WARMUP + r];
            var result = Dijkstra.run(graph, s, scattered, HOUR);
            for (int t : scattered) {
                if (!result.reached(t) || result.dist()[t] == 0) continue;
                int h = heuristic(graph, s, t, maxSpeed);
                ratios[at++] = Math.round(1000.0 * h / result.dist()[t]);
            }
        }
        var used = Arrays.copyOf(ratios, at);

        report.line("## 1. 휴리스틱은 얼마나 센가");
        report.line("");
        report.line("A* 의 이득은 전부 휴리스틱에서 나온다. `h(n) = 직선거리 / 최고속도` 가 실제 "
                + "소요시간의 몇 할인지 재면 A* 가 얼마나 치우칠 수 있는지가 먼저 보인다.");
        report.line("");
        report.line("| 항목 | 값 |");
        report.line("|---|---|");
        report.line("| 관측 최고 속도 (휴리스틱의 분모) | %.1f m/s · %.0f km/h |", maxSpeed, maxSpeed * 3.6);
        report.line("| `h / 실제 소요시간` 중앙값 | **%.1f%%** |", BenchSupport.median(used) / 10.0);
        report.line("| `h / 실제` p95 | %.1f%% |", BenchSupport.percentile(used, 0.95) / 10.0);
        report.line("| 표본 | 출발지 %d곳 × 후보 %d곳 |", RUNS, TARGETS);
        report.line("");
        report.line("휴리스틱이 실제 비용의 작은 몫만 설명한다. 분모가 **급행 지하철 속도**인데 "
                + "실제 이동은 도보·대기·환승이 섞여 있어서다 — 직선거리로는 설명되지 않는 비용이 "
                + "대부분이고, 그만큼 A* 는 목표 쪽으로 치우치지 못한다. "
                + "허용 휴리스틱을 유지하려면 분모를 더 낮출 수 없다(낮추면 답이 틀린다).");
        report.line("");
    }

    // ── 2. 목적지 1곳 — A* 가 유리한 조건 ────────────────────────────────────

    @Test
    @Order(3)
    void singleTarget() {
        report.line("## 2. 목적지 1곳 — A* 에게 가장 유리한 조건");
        report.line("");
        report.line("경로 상세(`GET /rooms/{id}/route`)가 이 모양이다. 출발지 1곳 → 목적지 1곳.");
        report.line("");
        report.line("| 후보 위치 | 방식 | 중앙값 | p95 | 확정 노드(중앙값) |");
        report.line("|---|---|---|---|---|");
        singleTargetCase("한 동네(반경 1km)", clustered[0]);
        singleTargetCase("서울 전역", scattered[0]);
        report.line("");
    }

    private void singleTargetCase(String label, int target) {
        var one = new int[] {target};
        var dij = measure(s -> {
            var r = Dijkstra.run(graph, s, one, HOUR);
            return r.settled();
        });
        var ast = measure(s -> astar(graph, s, target, HOUR, maxSpeed).settled());

        row(label, "다익스트라 (조기 종료)", dij);
        row("", "**A***", ast);
        report.line("| | 차이 | **%.2f×** | | %.2f× |",
                (double) dij.median / ast.median, (double) dij.settled / ast.settled);
    }

    // ── 3. 후보 N곳 — 실제 행렬의 모양 ───────────────────────────────────────

    @Test
    @Order(4)
    void manyTargets() {
        report.line("## 3. 후보 %d곳 — 행렬 한 줄의 실제 모양", TARGETS);
        report.line("");
        report.line("행렬(`POST /rooms/{id}/matrix`)은 사람마다 후보 전부까지의 시간을 한 줄로 낸다. "
                + "A* 는 목표가 여러 곳이면 치우칠 방향이 없으므로 **후보마다 따로** 돌려야 한다.");
        report.line("");
        report.line("| 후보 위치 | 방식 | 탐색 횟수 | 중앙값 | p95 | 확정 노드(중앙값) |");
        report.line("|---|---|---|---|---|---|");
        manyTargetCase("한 동네(반경 1km)", clustered);
        manyTargetCase("서울 전역", scattered);
        report.line("");
        report.line("`확정 노드` 는 탐색이 꺼낸 노드 수의 합이다. one-to-many 가 1회로 끝내는 일을 "
                + "1:1 은 %d 번 되풀이하면서 **출발지 근처를 매번 다시 확정한다** — "
                + "A* 가 한 번당 아끼는 몫보다 그 중복이 크다.", TARGETS);
        report.line("");
    }

    private void manyTargetCase(String label, int[] targets) {
        var oneToMany = measure(s -> Dijkstra.run(graph, s, targets, HOUR).settled());
        var dijEach = measure(s -> {
            int settled = 0;
            for (int t : targets) settled += Dijkstra.run(graph, s, new int[] {t}, HOUR).settled();
            return settled;
        });
        var astarEach = measure(s -> {
            int settled = 0;
            for (int t : targets) settled += astar(graph, s, t, HOUR, maxSpeed).settled();
            return settled;
        });

        report.line("| %s | 다익스트라 one-to-many (지금) | 1회 | %s | %s | %,d |",
                label, BenchSupport.fmtMs(oneToMany.median), BenchSupport.fmtMs(oneToMany.p95),
                oneToMany.settled);
        report.line("| | 다익스트라 1:1 × %d | %d회 | %s | %s | %,d |",
                TARGETS, TARGETS, BenchSupport.fmtMs(dijEach.median),
                BenchSupport.fmtMs(dijEach.p95), dijEach.settled);
        report.line("| | **A\\* 1:1 × %d** | %d회 | %s | %s | %,d |",
                TARGETS, TARGETS, BenchSupport.fmtMs(astarEach.median),
                BenchSupport.fmtMs(astarEach.p95), astarEach.settled);
        report.line("| | A\\* 가 one-to-many 대비 | | **%.2f×** | | %.2f× |",
                (double) astarEach.median / oneToMany.median,
                (double) astarEach.settled / oneToMany.settled);
    }

    // ── A* ──────────────────────────────────────────────────────────────────

    record Found(int dist, int settled) {}

    /**
     * A*. {@link Dijkstra#run} 과 같은 그래프·같은 힙을 쓰고 <b>우선순위만 다르다</b> —
     * 힙에 넣는 값이 {@code g} 가 아니라 {@code g + h} 다.
     *
     * <p>목표를 꺼낸 순간 그 노드의 {@code g} 가 최솟값임이 보장된다({@code h} 가 허용이므로).
     * 그 자리에서 멈춘다.
     */
    static Found astar(TransitGraph graph, int source, int target, int hour, double maxSpeedMps) {
        int n = graph.nodeCount();
        var g = new int[n];
        Arrays.fill(g, Dijkstra.UNREACHED);

        var queue = new LongMinHeap(1024);
        g[source] = 0;
        queue.push(LongMinHeap.pack(heuristic(graph, source, target, maxSpeedMps), source));

        int settled = 0;
        while (!queue.isEmpty()) {
            long top = queue.pop();
            int u = LongMinHeap.nodeOf(top);
            int f = LongMinHeap.distanceOf(top);

            // 더 짧은 길로 이미 다시 넣은 노드다. 다익스트라와 같은 방식으로 흘려보낸다.
            if (g[u] == Dijkstra.UNREACHED || f > g[u] + heuristic(graph, u, target, maxSpeedMps)) {
                continue;
            }
            settled++;
            if (u == target) return new Found(g[u], settled);

            int end = graph.edgeEnd(u);
            for (int e = graph.edgeStart(u); e < end; e++) {
                int v = graph.edgeTarget(e);
                int w = graph.weight(e, hour);
                if (w < 0) continue;   // NO_SERVICE
                int ng = g[u] + w;
                if (ng < g[v]) {
                    g[v] = ng;
                    queue.push(LongMinHeap.pack(ng + heuristic(graph, v, target, maxSpeedMps), v));
                }
            }
        }
        return new Found(Dijkstra.UNREACHED, settled);
    }

    /** 직선거리 ÷ 최고속도(초). 내림해서 실제 비용을 넘지 않게 한다 — 넘으면 답이 틀린다. */
    private static int heuristic(TransitGraph graph, int from, int to, double maxSpeedMps) {
        return (int) Math.floor(BenchSupport.meters(graph, from, to) / maxSpeedMps);
    }

    // ── 재는 도구 ───────────────────────────────────────────────────────────

    record Timing(long median, long p95, long settled) {}

    private Timing measure(java.util.function.IntUnaryOperator run) {
        for (int i = 0; i < WARMUP; i++) run.applyAsInt(sources[i]);
        var ns = new long[RUNS];
        var settled = new long[RUNS];
        for (int i = 0; i < RUNS; i++) {
            long t0 = System.nanoTime();
            settled[i] = run.applyAsInt(sources[WARMUP + i]);
            ns[i] = System.nanoTime() - t0;
        }
        return new Timing(
                BenchSupport.median(ns), BenchSupport.percentile(ns, 0.95), BenchSupport.median(settled));
    }

    private void row(String label, String method, Timing t) {
        report.line("| %s | %s | %s | %s | %,d |",
                label, method, BenchSupport.fmtMs(t.median), BenchSupport.fmtMs(t.p95), t.settled);
    }
}
