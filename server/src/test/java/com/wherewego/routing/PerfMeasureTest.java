package com.wherewego.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wherewego.graph.GraphLoader;
import com.wherewego.graph.NodeKind;
import com.wherewego.graph.TransitGraph;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * 성능 측정. 실제 그래프(활성 빌드)로 돈다 — 평소 {@code test} 에서는 빠지고
 * {@code ./gradlew perfTest} 로만 돈다. 결과는 {@code build/reports/perf/perf.md}.
 *
 * <ol>
 *   <li>그래프 적재 — DB 에서 읽어 CSR 을 만드는 시간, 실제로 늘어난 힙
 *   <li>다익스트라 1회 — 후보 10곳(한 동네 / 서울 전역)에서 멈출 때, 서울 전체를 훑을 때
 *   <li>CSR 대 객체 인접리스트 — 같은 그래프·같은 탐색을 {@code List<List<Edge>>} 로 돌린 대조군
 *   <li>행렬 API — 방을 만들어 {@code POST /matrix} 를 부른 응답시간(네트워크 제외)
 * </ol>
 *
 * <p>출발지·후보는 무작위 보행 노드이되 <b>서울 본 보행망에 이어진 노드만</b> 고른다. 섬에 떨어진
 * 노드를 후보로 잡으면 조기 종료가 걸리지 않아 매번 전체를 훑는다 — 실제 요청에서는 스냅이
 * 그런 노드를 고르지 않는다. 씨앗을 고정해 다시 돌려도 같은 표본이다.
 *
 * <p>힙은 {@code System.gc()} 뒤의 사용량 차이라 근사값이다. 비교(CSR 대 객체)에는 충분하다.
 */
@Tag("perf")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@EnabledIf("databaseIsUp")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PerfMeasureTest {

    static boolean databaseIsUp() {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 5432), 300);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static final int HOUR = 19;
    private static final long SEED = 42;
    private static final int WARMUP = 50;
    private static final int RUNS = 300;
    private static final int TARGETS = 10;

    @Autowired GraphLoader loader;
    @Autowired MockMvc mvc;

    private final JsonMapper json = JsonMapper.builder().build();
    private final StringBuilder report = new StringBuilder();

    private TransitGraph graph;
    private int[] sources;
    private int[] targets;          // 서울 전역에 흩어진 후보
    private int[] clustered;        // 한 동네(반경 1km)에 모인 후보 — 실제 방의 모양

    @BeforeAll
    void header() {
        var rt = Runtime.getRuntime();
        line("# 성능 측정");
        line("");
        line("- 측정 시각 %s", LocalDateTime.now().withNano(0));
        line("- JVM %s · 최대 힙 %dMB · 코어 %d", System.getProperty("java.version"),
                rt.maxMemory() >> 20, rt.availableProcessors());
        line("- 시간대 %d시 · 씨앗 %d · 예열 %d회 · 측정 %d회", HOUR, SEED, WARMUP, RUNS);
        line("");
    }

    @AfterAll
    void write() throws IOException {
        Path out = Path.of("build/reports/perf/perf.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report, StandardCharsets.UTF_8);
        System.out.println(report);
    }

    // ── 1. 그래프 적재 ───────────────────────────────────────────────────────

    @Test
    @Order(1)
    void graphLoad() {
        long before = usedHeap();
        long[] ms = new long[3];
        for (int i = 0; i < ms.length; i++) {
            graph = null;
            usedHeap();                          // 앞 회차 그래프를 치우고 잰다
            long t0 = System.nanoTime();
            graph = loader.load();
            ms[i] = (System.nanoTime() - t0) / 1_000_000;
        }
        long retained = usedHeap() - before;

        line("## 1. 그래프 적재");
        line("");
        line("빌드 #%d · 노드 %,d · 엣지 %,d", graph.buildId(), graph.nodeCount(), graph.edgeCount());
        line("");
        line("| 항목 | 값 |");
        line("|---|---|");
        line("| 적재 시간 (1회차 / 2회차 / 3회차) | %,d / %,d / %,d ms |", ms[0], ms[1], ms[2]);
        line("| 배열 크기 합 (approximateBytes) | %.1f MB |", graph.approximateBytes() / 1048576.0);
        line("| 실제로 늘어난 힙 (GC 후) | %.1f MB |", retained / 1048576.0);
        line("");

        pickSample();
    }

    /** 본 보행망에 이어진 보행 노드에서 출발지와 후보를 고른다. */
    private void pickSample() {
        int hub = graph.findStop(com.wherewego.graph.TransitMode.SUBWAY, "시청");
        assertThat(hub).isGreaterThanOrEqualTo(0);
        var reach = Dijkstra.run(graph, hub, null, HOUR);

        var walk = new ArrayList<Integer>();
        for (int n = 0; n < graph.nodeCount(); n++) {
            if (graph.kindOf(n) == NodeKind.WALK && reach.reached(n)) walk.add(n);
        }
        var rng = new Random(SEED);
        sources = new int[WARMUP + RUNS];
        for (int i = 0; i < sources.length; i++) sources[i] = walk.get(rng.nextInt(walk.size()));
        targets = new int[TARGETS];
        for (int i = 0; i < TARGETS; i++) targets[i] = walk.get(rng.nextInt(walk.size()));

        int center = walk.get(rng.nextInt(walk.size()));
        var near = walk.stream().filter(n -> meters(center, n) <= CLUSTER_M).toList();
        clustered = new int[TARGETS];
        for (int i = 0; i < TARGETS; i++) clustered[i] = near.get(rng.nextInt(near.size()));
    }

    private static final double CLUSTER_M = 1_000;

    /** 두 노드의 직선거리(m). 서울 안이면 등장방형 근사로 충분하다. */
    private double meters(int a, int b) {
        double lat = Math.toRadians((graph.latOf(a) + graph.latOf(b)) / 2);
        double dx = Math.toRadians(graph.lngOf(b) - graph.lngOf(a)) * Math.cos(lat);
        double dy = Math.toRadians(graph.latOf(b) - graph.latOf(a));
        return 6_371_000 * Math.hypot(dx, dy);
    }

    // ── 2. 다익스트라 1회 ────────────────────────────────────────────────────

    @Test
    @Order(2)
    void dijkstraOnce() {
        var toCluster = measure(s -> Dijkstra.run(graph, s, clustered, HOUR).settled());
        var toTargets = measure(s -> Dijkstra.run(graph, s, targets, HOUR).settled());
        var full = measure(s -> Dijkstra.run(graph, s, null, HOUR).settled());

        line("## 2. 다익스트라 1회");
        line("");
        line("출발지는 무작위 보행 노드 %d곳. 행렬 한 줄이 이 탐색 한 번이다.", RUNS);
        line("");
        line("| 탐색 | 중앙값 | p95 | 최대 | 확정 노드(중앙값) |");
        line("|---|---|---|---|---|");
        row("후보 %d곳 · 반경 1km 에 모임".formatted(TARGETS), toCluster);
        row("후보 %d곳 · 서울 전역에 흩어짐".formatted(TARGETS), toTargets);
        row("서울 전체 (멈추지 않음)", full);
        line("");
        line("후보를 다 확정하면 멈추는데, 확정한 노드는 전체 대비 모인 후보 %.0f%% · 흩어진 후보 %.0f%% 다. "
                + "멈추는 시점은 가장 먼 후보가 정한다 — 출발지가 서울 곳곳이면 후보가 모여 있어도 "
                + "거기까지 가는 동안 도시 대부분을 훑는다.",
                100.0 * toCluster.settledMedian / full.settledMedian,
                100.0 * toTargets.settledMedian / full.settledMedian);
        line("");
    }

    // ── 3. CSR 대 객체 인접리스트 ─────────────────────────────────────────────

    /** 흔히 쓰는 모양의 대조군. 엣지 하나가 객체 하나, 시간대 배열도 엣지마다 따로 든다. */
    record Edge(int to, int weight, short[] byHour) {}

    @Test
    @Order(3)
    void csrVersusObjects() {
        long before = usedHeap();
        List<List<Edge>> adj = toObjects(graph);
        long objectBytes = usedHeap() - before;

        // 같은 답을 내는지부터 — 다른 탐색을 재면 비교가 아니다
        for (int i = 0; i < 20; i++) {
            int s = sources[i];
            assertThat(dijkstraObjects(adj, s, null, HOUR))
                    .isEqualTo(Dijkstra.run(graph, s, null, HOUR).dist());
        }

        // JIT 치우침을 줄이려고 번갈아 여러 판 돌리고, 판마다 중앙값을 모아 다시 중앙값을 낸다
        long[] csr = new long[5];
        long[] obj = new long[5];
        for (int round = 0; round < 5; round++) {
            obj[round] = measure(s -> { dijkstraObjects(adj, s, null, HOUR); return 0; }).median;
            csr[round] = measure(s -> Dijkstra.run(graph, s, null, HOUR).settled()).median;
        }
        long csrMs = median(csr);
        long objMs = median(obj);
        long n = graph.nodeCount();
        long e = graph.edgeCount();
        // CSR 에서 탐색이 쓰는 배열만: head · edgeTo · edgeWeight · edgeHourly · hourly.
        // approximateBytes 에서 좌표·이름표·DB id·거리·종류 배열을 뺀다.
        long csrSearch = graph.approximateBytes() - (8 * n + 2 * n + 8 * n + 8 * n) - (8 * e + e);

        line("## 3. CSR 대 객체 인접리스트");
        line("");
        line("같은 그래프를 `List<List<Edge>>`(엣지마다 객체, 시간대 배열도 엣지마다)로 옮겨 같은 "
                + "다익스트라·같은 힙으로 돌렸다. 서울 전체 탐색, 5판 번갈아 잰 판별 중앙값의 중앙값. "
                + "두 쪽의 거리 배열이 20개 출발지에서 모두 같음을 먼저 확인했다.");
        line("");
        line("| | CSR (배열) | 객체 인접리스트 | 배 |");
        line("|---|---|---|---|");
        line("| 탐색 1회 (서울 전체) | %s | %s | %.2f× |", fmtMs(csrMs), fmtMs(objMs), (double) objMs / csrMs);
        line("| 탐색에 쓰는 구조의 힙 | %.1f MB | %.1f MB | %.1f× |",
                csrSearch / 1048576.0, objectBytes / 1048576.0, (double) objectBytes / csrSearch);
        line("| 엣지 하나당 | %.1f B | %.1f B | |", (double) csrSearch / e, (double) objectBytes / e);
        line("");
        line("두 쪽 모두 탐색에 쓰는 것(이웃·가중치·시간대)만 센 값이다. 객체 쪽은 유리하게 만든 대조군이다 — "
                + "노드 번호가 0..N-1 int 이고, 엣지는 record, 리스트는 크기를 맞춰 잡았다. "
                + "`Map<Long, List<Edge>>` 처럼 박싱된 키를 쓰면 이보다 커진다.");
        line("");
        assertThat(adj).hasSize(graph.nodeCount());   // 측정 동안 adj 가 살아 있도록
    }

    private static List<List<Edge>> toObjects(TransitGraph g) {
        var adj = new ArrayList<List<Edge>>(g.nodeCount());
        for (int u = 0; u < g.nodeCount(); u++) {
            var out = new ArrayList<Edge>(g.edgeEnd(u) - g.edgeStart(u));
            for (int e = g.edgeStart(u); e < g.edgeEnd(u); e++) {
                short[] byHour = null;
                int base = g.weight(e, Dijkstra.NO_HOUR);
                for (int h = 0; h < 24; h++) {
                    if (g.weight(e, h) != base) {
                        byHour = new short[24];
                        for (int k = 0; k < 24; k++) byHour[k] = (short) g.weight(e, k);
                        break;
                    }
                }
                out.add(new Edge(g.edgeTarget(e), base, byHour));
            }
            adj.add(out);
        }
        return adj;
    }

    /** {@link Dijkstra#run} 과 같은 알고리즘·같은 힙. 그래프 표현만 다르다. */
    private static int[] dijkstraObjects(List<List<Edge>> adj, int source, int[] targets, int hour) {
        int n = adj.size();
        var dist = new int[n];
        var prevNode = new int[n];
        Arrays.fill(dist, Dijkstra.UNREACHED);
        Arrays.fill(prevNode, -1);
        var queue = new LongMinHeap(1024);
        dist[source] = 0;
        queue.push(LongMinHeap.pack(0, source));
        while (!queue.isEmpty()) {
            long top = queue.pop();
            int u = LongMinHeap.nodeOf(top);
            int d = LongMinHeap.distanceOf(top);
            if (d > dist[u]) continue;
            for (Edge edge : adj.get(u)) {
                int w = edge.byHour() == null ? edge.weight() : edge.byHour()[hour];
                if (w < 0) continue;
                int nd = d + w;
                if (nd < dist[edge.to()]) {
                    dist[edge.to()] = nd;
                    prevNode[edge.to()] = u;
                    queue.push(LongMinHeap.pack(nd, edge.to()));
                }
            }
        }
        return dist;
    }

    // ── 4. 행렬 API ─────────────────────────────────────────────────────────

    @Test
    @Order(4)
    void matrixApi() throws Exception {
        line("## 4. 행렬 API");
        line("");
        line("방을 만들고 사람마다 출발지(서울 무작위 좌표)를, 후보는 한 동네(반경 1~3km)에 정한 뒤 "
                + "`POST /rooms/{id}/matrix` 를 부른 응답시간. MockMvc 라 네트워크는 빠진다. 예열 3회 뒤 15회.");
        line("");
        line("| 사람 × 후보 | 중앙값 | p95 | 서버 계산(elapsedMs 중앙값) | 확정 노드(합) |");
        line("|---|---|---|---|---|");
        for (int[] size : new int[][] {{2, 5}, {5, 10}, {10, 20}}) {
            matrixCase(size[0], size[1]);
        }
        line("");
        line("다익스트라 횟수는 사람 수와 같고 후보 수와 무관하다(one-to-many).");
        line("");
    }

    private void matrixCase(int people, int places) throws Exception {
        var rng = new Random(SEED + people * 100L + places);
        var sessions = new ArrayList<MockHttpSession>();
        for (int i = 0; i < people; i++) {
            sessions.add(signUp("p%d_%d_%d".formatted(people, places, i)));
        }
        var owner = sessions.getFirst();
        String roomId = json.readTree(mvc.perform(body(post("/api/v1/rooms"), owner, "{\"title\":\"측정\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("roomId").asString();
        for (var s : sessions.subList(1, sessions.size())) {
            mvc.perform(post("/api/v1/rooms/" + roomId + "/members").session(s).with(csrf()))
                    .andExpect(status().isOk());
        }
        for (var s : sessions) {
            int n = sources[rng.nextInt(sources.length)];
            mvc.perform(body(put("/api/v1/rooms/" + roomId + "/members/me/origin"), s,
                            "{\"lng\":%s,\"lat\":%s}".formatted(graph.lngOf(n), graph.latOf(n))))
                    .andExpect(status().isOk());
        }
        for (int i = 0; i < places; i++) {
            int n = clusteredNear(rng);
            mvc.perform(body(post("/api/v1/rooms/" + roomId + "/bookmarks"), owner,
                            "{\"name\":\"후보%d\",\"lng\":%s,\"lat\":%s}".formatted(i, graph.lngOf(n), graph.latOf(n))))
                    .andExpect(status().isCreated());
        }

        var total = new long[15];
        var server = new long[15];
        int expanded = 0;
        for (int i = -3; i < 15; i++) {
            long t0 = System.nanoTime();
            var body = mvc.perform(body(post("/api/v1/rooms/" + roomId + "/matrix"), owner, "{\"departureHour\":%d}".formatted(HOUR)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            long ns = System.nanoTime() - t0;
            if (i < 0) continue;
            var stats = json.readTree(body).get("stats");
            total[i] = ns;
            server[i] = stats.get("elapsedMs").asLong();
            expanded = stats.get("expandedNodes").asInt();
            assertThat(stats.get("dijkstraRuns").asInt()).isEqualTo(people);
        }
        Arrays.sort(total);
        line("| %d × %d | %s | %s | %d ms | %,d |", people, places,
                fmtMs(median(total)), fmtMs(total[(int) Math.ceil(0.95 * total.length) - 1]),
                median(server), expanded);
    }

    /** 행렬 후보는 한 동네에 모인다 — 친구들이 한 지역의 가게를 두고 고르는 모양. */
    private int clusteredNear(Random rng) {
        int c = clustered[0];
        for (int tries = 0; tries < 10_000; tries++) {
            int n = sources[rng.nextInt(sources.length)];
            if (meters(c, n) <= 3 * CLUSTER_M) return n;
        }
        return clustered[rng.nextInt(clustered.length)];
    }

    private MockHttpSession signUp(String loginId) throws Exception {
        var session = new MockHttpSession();
        mvc.perform(body(post("/api/v1/auth/signup"), session, """
                        {"nickname":"%s","loginId":"%s","password":"perf-pw-123","passwordConfirm":"perf-pw-123"}
                        """.formatted(loginId, loginId)))
                .andExpect(status().isCreated());
        return session;
    }

    private static MockHttpServletRequestBuilder body(
            MockHttpServletRequestBuilder builder, MockHttpSession session, String content) {
        return builder.session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(content);
    }

    // ── 재는 도구 ───────────────────────────────────────────────────────────

    /** 예열 뒤 RUNS 번. 시간은 나노초. */
    private Timing measure(java.util.function.IntUnaryOperator run) {
        for (int i = 0; i < WARMUP; i++) run.applyAsInt(sources[i]);
        var ns = new long[RUNS];
        var settled = new long[RUNS];
        for (int i = 0; i < RUNS; i++) {
            long t0 = System.nanoTime();
            settled[i] = run.applyAsInt(sources[WARMUP + i]);
            ns[i] = System.nanoTime() - t0;
        }
        Arrays.sort(ns);
        return new Timing(median(ns), ns[(int) Math.ceil(0.95 * RUNS) - 1], ns[RUNS - 1], median(settled));
    }

    record Timing(long median, long p95, long max, long settledMedian) {}

    private void row(String label, Timing t) {
        line("| %s | %s | %s | %s | %,d |", label, fmtMs(t.median), fmtMs(t.p95), fmtMs(t.max), t.settledMedian);
    }

    private static long median(long[] values) {
        var copy = values.clone();
        Arrays.sort(copy);
        return copy[copy.length / 2];
    }

    private static String fmtMs(long ns) {
        return "%.1f ms".formatted(ns / 1e6);
    }

    private static long usedHeap() {
        for (int i = 0; i < 3; i++) {
            System.gc();
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private void line(String format, Object... args) {
        report.append(args.length == 0 ? format : format.formatted(args)).append('\n');
    }
}
