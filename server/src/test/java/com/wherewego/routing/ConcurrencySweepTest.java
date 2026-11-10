package com.wherewego.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.wherewego.graph.GraphLoader;
import com.wherewego.graph.NodeKind;
import com.wherewego.graph.TransitGraph;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntUnaryOperator;
import javax.sql.DataSource;
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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 동시 요청을 10 부터 100 까지 올리며 <b>그래프를 어디에 두는지</b>를 비교한다.
 *
 * <p>"메모리에 올려서 얻는 게 SQL 실행 시간 정도 아니냐", "사용자가 많아지면 메모리가 터지지
 * 않나" 에 숫자로 답하기 위한 측정이다. 세 가지를 재고, 각각 무엇을 사는지 다르다.
 *
 * <ol>
 *   <li><b>인메모리 CSR</b> — 지금 쓰는 것. 그래프를 한 번 읽어 배열로 들고 탐색이 그 안에서 돈다
 *   <li><b>DB 간선 조회</b> — 탐색은 앱에서 하고 <b>이웃만 매번 SQL 로</b> 가져온다.
 *       알고리즘·자료구조·확장 노드 수가 전부 같고 이웃을 어디서 읽는지만 다르다
 *   <li><b>사전계산 조회</b> — 출발지×후보×시간대의 답을 미리 표에 넣어 두고 요청은 인덱스
 *       조회 한 번으로 끝낸다. "실무는 SQL" 에 해당하는 설계이고, <b>가장 빠르다</b> —
 *       대신 저장 비용을 §4 에서 센다
 * </ol>
 *
 * <h2>무엇을 한 단위로 보는가</h2>
 *
 * §2 는 <b>노드 {@value #CAPPED_SETTLED} 개 확장</b>을 한 단위로 둔다. 1·2번이 완전히 같은
 * 일을 하므로 그대로 나란히 놓을 수 있다 — 2번으로 탐색을 끝까지 돌리면 한 번에 수십 초가
 * 걸려 동시 요청을 100 까지 올릴 수 없다. §3 은 단위를 <b>행렬 한 줄</b>(출발지 1곳 → 후보
 * {@value #TARGETS} 곳)로 바꿔 실제 요청을 재고, 2번은 §2 의 확장당 비용으로 외삽한다.
 *
 * <h2>메모리</h2>
 *
 * 그래프 자체는 공유하는 불변 구조라 <b>동시 사용자와 무관한 상수</b>다. 늘어나는 것은 탐색
 * 1회가 잡는 작업공간({@code dist}·{@code prevNode}·{@code prevEdge})이고 이게 동시 탐색 수에
 * 비례한다. §3 은 <b>요청마다 새로 잡는 쪽</b>과 <b>스레드별로 재사용하는 쪽</b>을 같이 재서
 * 그 비례가 상수로 바뀌는지 본다.
 *
 * <p>작은 힙으로 돌려야 무너지는 지점이 보인다:
 *
 * <pre>
 *   ./gradlew sweepTest                 # 힙 512m (기본)
 *   ./gradlew sweepTest -PsweepHeap=2g  # 힙 2g
 * </pre>
 *
 * 결과는 {@code build/reports/perf/concurrency.md}.
 */
@Tag("perf")
@SpringBootTest(properties = {
    // 동시 요청 100 에 풀 10(기본)이면 재는 게 DB 가 아니라 풀 대기다. 넉넉히 준다.
    "spring.datasource.hikari.maximum-pool-size=30",
    "spring.datasource.hikari.connection-timeout=60000",
})
@EnabledIf("databaseIsUp")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConcurrencySweepTest {

    static boolean databaseIsUp() {
        return BenchSupport.databaseIsUp();
    }

    static final int POOL_SIZE = 30;

    private static final int HOUR = 19;
    private static final long SEED = 42;
    private static final int TARGETS = 20;

    /** 동시 요청 수. 사용자가 그린 그대로 10 부터 100 까지 10 단위. */
    /**
     * 동시 요청 수준. <b>두 배씩</b> 올린다.
     *
     * <p>처음엔 10 부터 100 까지 10 단위였는데, 코어가 16 개라 탐색이 동시 16 에서 CPU 를
     * 포화시킨다. 10 단위면 10 칸 중 9 칸이 이미 포화된 구간이고, 정작 코어 수에 따라 성능이
     * 늘어나는 구간에는 점이 하나뿐이었다. 두 배씩 올리면 포화 전·포화·포화 후에 고르게 점이
     * 찍히고, 코어 2 개짜리 서버로 옮겨 돌려도 포화 지점(2)이 잡힌다. 100 은 처음 질문의 상한이라 남긴다.
     */
    private static final int[] LEVELS = {1, 2, 4, 8, 16, 32, 64, 100};

    /** §2 의 한 단위. 이 수만큼 노드를 확장하면 멈춘다. */
    private static final int CAPPED_SETTLED = 500;

    /** 동시성 수준마다 보낼 요청 수 = 동시 요청 수 × 이 배수. */
    private static final int REQUEST_FACTOR = 3;

    /** 수준마다 재지 않고 버리는 예열 요청 수. DB 쪽이 느려서 적게 둔다. */
    private static final int WARMUP_REQUESTS = 5;

    /** 수준마다 보내는 요청의 바닥. 동시 1 에서 `1 × 3 = 3` 개로는 p95 를 낼 수 없다. */
    private static final int MIN_REQUESTS = 30;

    /**
     * §3 에서 칸마다 반복하는 횟수. 그중 <b>중앙값이 가장 작은 회차</b>를 쓴다.
     *
     * <p>같은 머신의 다른 프로세스는 측정을 느리게만 만들 수 있고 빠르게 만들 수는 없다.
     * 그래서 여러 회차 중 가장 빠른 것이 방해가 가장 적었던 상태에 가장 가깝다. 평균을 내면
     * 망가진 회차가 섞인다. 반복 없이 한 번만 재던 첫 하네스는 동시 50 에서 3,425ms, 동시 60
     * 에서 177ms 처럼 간섭 맞은 칸을 그대로 표에 올렸다.
     *
     * <p>계속되는 부하(측정 내내 다른 무거운 작업)는 이걸로 걸러지지 않는다 — 세 회차가 똑같이
     * 느려진다. 그래서 회차 간 차이를 같이 적어 읽는 사람이 판단하게 한다.
     */
    private static final int REPEATS = 3;

    @Autowired GraphLoader loader;
    @Autowired DataSource dataSource;

    private JdbcTemplate jdbc;
    private final BenchSupport.Report report = new BenchSupport.Report();

    private TransitGraph graph;
    private int[] sources;      // 출발지 표본
    private int[] targets;      // 후보 — 서울 전역에 흩어진다(보수적인 쪽)
    private long baselineBytes; // 그래프를 올리기 전의 힙 — Spring·Hikari·JVM
    private long graphBytes;

    @BeforeAll
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);

        baselineBytes = BenchSupport.usedHeapAfterGc();
        graph = loader.load();
        graphBytes = BenchSupport.usedHeapAfterGc() - baselineBytes;

        int[] walk = BenchSupport.connectedWalkNodes(graph, HOUR);
        var rng = new Random(SEED);
        sources = BenchSupport.pick(walk, 400, rng);
        targets = BenchSupport.pick(walk, TARGETS, rng);

        report.header("동시 요청 스윕 — 그래프를 어디에 두는가",
                "시간대 %d시 · 씨앗 %d · 후보 %d곳(서울 전역) · 커넥션 풀 %d · 수준마다 요청 %d×동시수"
                        .formatted(HOUR, SEED, TARGETS, POOL_SIZE, REQUEST_FACTOR));
        report.line("빌드 #%d · 노드 %,d · 엣지 %,d", graph.buildId(), graph.nodeCount(), graph.edgeCount());
        report.line("");
        report.line("후보는 **서울 전역에 흩어뜨렸다**. 실제 방은 후보가 한 동네에 모이는 쪽이 많고 "
                + "그러면 조기 종료가 더 먹어서 숫자가 좋아진다 — 여기서는 **불리한 쪽으로** 잡는다.");
        report.line("");
        memoryBreakdown();
    }

    /** §0 — 메모리가 무엇으로 이루어지는지. 추측 대신 재서 적는다. */
    private void memoryBreakdown() {
        var rt = Runtime.getRuntime();
        var shape = shapeOf(sources[0], targets, HOUR);

        // 힙 배열은 1024 에서 두 배씩 늘어난다. 최대 원소 수를 담을 수 있는 용량.
        long heapCapacity = 1024;
        while (heapCapacity < shape.maxQueue()) heapCapacity *= 2;
        long queueBytes = heapCapacity * 8;

        long nodeInts = 4L * graph.nodeCount();
        long workspace = 3 * nodeInts + graph.nodeCount() + queueBytes;

        var inMem = allocationShape(cap -> s -> cappedInMemoryCount(s, HOUR, cap));
        var db = allocationShape(cap -> s -> cappedViaDbCount(s, HOUR, cap));
        var dbLean = allocationShape(cap -> s -> cappedViaDbLeanCount(s, HOUR, cap));

        long inMemNs = latencyPerSearch(s -> cappedInMemoryCount(s, HOUR, CAPPED_SETTLED));
        long dbNs = latencyPerSearch(s -> cappedViaDbCount(s, HOUR, CAPPED_SETTLED));
        long dbLeanNs = latencyPerSearch(s -> cappedViaDbLeanCount(s, HOUR, CAPPED_SETTLED));

        report.line("## 0. 메모리는 무엇으로 이루어지나");
        report.line("");
        report.line("서울 전역 후보 %d곳 탐색 1회의 모양: 확정 노드 **%,d** · 힙에 넣은 횟수 %,d · "
                + "힙 최대 원소 %,d개.", TARGETS, shape.settled(), shape.pushes(), shape.maxQueue());
        report.line("");
        report.line("| 항목 | 크기 | 동시 사용자에 비례하나 |");
        report.line("|---|---|---|");
        report.line("| JVM 베이스라인 (Spring · Hikari · JDBC) | %.0f MB | 아니다 — 상수 |",
                baselineBytes / 1048576.0);
        report.line("| 공유 그래프 (CSR 배열, 불변) | **%.1f MB** | **아니다 — 상수** |",
                graphBytes / 1048576.0);
        report.line("| `dist` `int[%,d]` | %.2f MB | 그렇다 |", graph.nodeCount(), nodeInts / 1048576.0);
        report.line("| `prevNode` `int[%,d]` | %.2f MB | 그렇다 |", graph.nodeCount(), nodeInts / 1048576.0);
        report.line("| `prevEdge` `int[%,d]` | %.2f MB | 그렇다 |", graph.nodeCount(), nodeInts / 1048576.0);
        report.line("| `wanted` `boolean[%,d]` | %.2f MB | 그렇다 |",
                graph.nodeCount(), (double) graph.nodeCount() / 1048576.0);
        report.line("| `LongMinHeap` `long[%,d]` (최대 원소 %,d) | **%.2f MB** | 그렇다 |",
                heapCapacity, shape.maxQueue(), queueBytes / 1048576.0);
        report.line("| **탐색 1회 작업공간 합** | **%.2f MB** | **동시 탐색 수 × 이 값** |",
                workspace / 1048576.0);
        report.line("");
        report.line("그래프는 요청마다 복사되지 않는다. `GraphCache` 가 들고 있는 `volatile` 참조 "
                + "하나를 모든 요청이 같이 읽는다(불변이라 잠금이 없다). **결과 캐시처럼 키가 늘면 "
                + "커지는 구조가 아니다.**");
        report.line("");
        report.line("그래서 `살아 있는 힙 = 베이스라인 + 그래프 + 동시 탐색 수 × %.2f MB` 다. "
                + "앞의 두 항이 %.0f MB 를 먼저 먹으니 %dMB 힙이면 산술적으로 "
                + "**동시 탐색 %d개**까지 들어간다.",
                workspace / 1048576.0, (baselineBytes + graphBytes) / 1048576.0,
                rt.maxMemory() >> 20,
                (int) ((rt.maxMemory() - baselineBytes - graphBytes) / workspace));
        report.line("");
        report.line("**그런데 실제로는 그보다 훨씬 먼저 찬다.** §3 의 `요청마다 할당` 피크 힙이 "
                + "산술 상한보다 훨씬 낮은 동시성에서 이미 힙 천장 근처에 붙는다. `살아 있는 양`과 `쓰는 양`이 다르기 때문이다 — 요청마다 "
                + "%.2f MB 를 새로 잡고 버리므로 할당 속도가 초당 수백 MB 가 되고, G1 은 "
                + "힙이 거의 찰 때까지 수거를 미룬다. 그래서 **한계를 정하는 것은 살아 있는 양이 "
                + "아니라 할당 속도**이고, 아래에서 재는 `작업공간 풀` 이 바로 그 할당을 없애는 장치다.",
                workspace / 1048576.0);
        report.line("");
        report.line("### DB 간선 조회는 메모리를 아끼나");
        report.line("");
        report.line("아니다. **탐색이 여전히 앱에서 돌기 때문에 작업공간은 똑같이 든다** — "
                + "DB 로 옮긴 것은 이웃 정보뿐이다. 그 위에 질의마다 쓰레기가 더 생긴다. "
                + "같은 단위작업(노드 %,d개 확장)의 스레드 할당량을 재면:", CAPPED_SETTLED);
        report.line("");
        report.line("확장 %,d회와 %,d회에서 각각 재서 **확장 횟수와 무관한 고정분**과 "
                + "**확장 하나가 더 얹는 몫**을 갈라냈다.", CAPPED_SETTLED, 3 * CAPPED_SETTLED);
        report.line("");
        report.line("세 번째 줄은 **쓰레기를 최소로 줄인 DB 방식**이다. 커넥션과 "
                + "`PreparedStatement` 를 탐색 하나 동안 재사용하고, 결과를 리스트나 레코드로 "
                + "옮기지 않고 `ResultSet` 에서 바로 완화하고, 파라미터 박싱도 없앴다 — "
                + "`JdbcTemplate` 을 걷어낸 손수 JDBC 다.");
        report.line("");
        report.line("| 방식 | 고정분 (작업공간) | 확장당 증가분 | 서울 전역 탐색 1회 총 할당 |");
        report.line("|---|---|---|---|");
        report.line("| 인메모리 CSR | %.2f MB | %.0f B | **%.1f MB** |",
                inMem.fixed() / 1048576.0, inMem.perExpansion(),
                inMem.project(shape.settled()) / 1048576.0);
        report.line("| DB 간선 조회 (`JdbcTemplate`) | %.2f MB | %.0f B | %.0f MB |",
                db.fixed() / 1048576.0, db.perExpansion(),
                db.project(shape.settled()) / 1048576.0);
        report.line("| DB 간선 조회 (쓰레기 최소화) | %.2f MB | **%.0f B** | **%.0f MB** |",
                dbLean.fixed() / 1048576.0, dbLean.perExpansion(),
                dbLean.project(shape.settled()) / 1048576.0);
        report.line("");
        report.line("인메모리는 고정분이 거의 전부다 — 확장당 증가분이 %.0f B 로, 배열을 훑는 것 "
                + "말고는 객체를 만들지 않는다.", inMem.perExpansion());
        report.line("");
        report.line("객체를 안 만들면 어떻게 되나 — **확장당 %.0f B 에서 %.0f B 로 %.1f배 줄었다. "
                + "그런데 0 이 되지는 않는다.** 질의마다 드라이버가 `ResultSet` 과 와이어 프로토콜 "
                + "버퍼를 만들어야 하고, 그건 애플리케이션 코드로 없앨 수 없다. "
                + "서울 전역 탐색 1회로 환산하면 인메모리 %.1f MB 대 **%.0f MB**, 여전히 약 %.0f배다.",
                db.perExpansion(), dbLean.perExpansion(),
                db.perExpansion() / Math.max(1.0, dbLean.perExpansion()),
                inMem.project(shape.settled()) / 1048576.0,
                dbLean.project(shape.settled()) / 1048576.0,
                (double) dbLean.project(shape.settled()) / inMem.project(shape.settled()));
        report.line("");
        report.line("속도도 같이 좋아졌다. 구문을 재사용하니 파싱·플랜이 사라지고 풀에서 빌리는 "
                + "비용도 없다 — 노드 %,d개 확장 1회가 %s → **%s**. 그래도 인메모리(%s)와는 "
                + "자리가 다르다.",
                CAPPED_SETTLED, BenchSupport.fmtMs(dbNs), BenchSupport.fmtMs(dbLeanNs),
                BenchSupport.fmtMs(inMemNs));
        report.line("");
        report.line("대가가 하나 생긴다. 커넥션을 탐색이 끝날 때까지 쥐고 있으므로 "
                + "**동시 탐색 수가 커넥션 풀 크기(%d)에 묶인다.** 질의마다 빌리고 돌려주면 "
                + "그 제약은 없지만 그게 바로 줄이려던 비용이다 — 쓰레기와 동시성이 맞바꿔진다.",
                POOL_SIZE);
        report.line("");
        report.line("결론은 그대로다. 그래프를 DB 에 두면 **%.1f MB 를 아끼고**, 가장 알뜰하게 "
                + "짜도 **탐색 1회에 %.0f MB 의 질의 쓰레기를 얻는다.** "
                + "(이 측정의 DB 방식들도 노드 id ↔ 배열 첨자 변환 때문에 그래프를 들고 있다. "
                + "진짜로 %.0fMB 를 아끼는 설계라면 그 변환을 따로 마련해야 한다.)",
                graphBytes / 1048576.0, dbLean.project(shape.settled()) / 1048576.0,
                graphBytes / 1048576.0);
        report.line("");
        report.line("- 측정 JVM 최대 힙 **%d MB** · 코어 %d", rt.maxMemory() >> 20, rt.availableProcessors());
        report.line("");
    }

    /**
     * @param settled 확정한 노드 수
     * @param pushes 힙에 넣은 횟수 — 같은 노드가 여러 번 들어간다
     * @param maxQueue 힙에 동시에 들어 있던 최대 원소 수. 힙 배열 용량을 정한다
     */
    record SearchShape(int settled, int pushes, int maxQueue) {}

    /** 탐색 1회의 모양을 센다. {@link Dijkstra#run} 과 같은 알고리즘이고 계수기만 붙였다. */
    private SearchShape shapeOf(int source, int[] goals, int hour) {
        int n = graph.nodeCount();
        var dist = new int[n];
        Arrays.fill(dist, Dijkstra.UNREACHED);
        var wanted = new boolean[n];
        int remaining = 0;
        for (int t : goals) {
            if (t >= 0 && !wanted[t]) {
                wanted[t] = true;
                remaining++;
            }
        }

        var queue = new LongMinHeap(1024);
        dist[source] = 0;
        queue.push(LongMinHeap.pack(0, source));

        int settled = 0;
        int pushes = 1;
        int maxQueue = 1;
        while (!queue.isEmpty()) {
            long top = queue.pop();
            int u = LongMinHeap.nodeOf(top);
            int d = LongMinHeap.distanceOf(top);
            if (d > dist[u]) continue;
            settled++;
            if (wanted[u]) {
                wanted[u] = false;
                if (--remaining == 0) break;
            }
            int end = graph.edgeEnd(u);
            for (int e = graph.edgeStart(u); e < end; e++) {
                int v = graph.edgeTarget(e);
                int w = graph.weight(e, hour);
                if (w < 0) continue;
                int nd = d + w;
                if (nd < dist[v]) {
                    dist[v] = nd;
                    queue.push(LongMinHeap.pack(nd, v));
                    pushes++;
                    if (queue.size() > maxQueue) maxQueue = queue.size();
                }
            }
        }
        return new SearchShape(settled, pushes, maxQueue);
    }

    /**
     * 할당량의 모양.
     *
     * @param fixed 확장 횟수와 무관하게 드는 몫 (작업공간 배열)
     * @param perExpansion 확장 하나가 더 얹는 몫
     */
    record AllocShape(long fixed, double perExpansion) {

        long project(int settled) {
            return fixed + Math.round(perExpansion * settled);
        }
    }

    /**
     * 확장 횟수를 둘로 달리 재서 고정분과 기울기를 가른다.
     *
     * <p>한 지점만 재서 `할당량 ÷ 확장 횟수` 를 쓰면 작업공간 2.5MB 가 확장 횟수로 나눠지면서
     * "확장 하나에 5KB" 같은 엉뚱한 값이 나온다. 그건 확장의 비용이 아니라 셋업의 비용이다.
     */
    private AllocShape allocationShape(java.util.function.IntFunction<IntUnaryOperator> workFor) {
        int low = CAPPED_SETTLED;
        int high = 3 * CAPPED_SETTLED;
        long atLow = allocationAt(workFor.apply(low));
        long atHigh = allocationAt(workFor.apply(high));
        double slope = (double) (atHigh - atLow) / (high - low);
        return new AllocShape(Math.round(atLow - slope * low), slope);
    }

    /** 탐색 1회의 소요시간(ns). 예열 뒤 5회의 중앙값. */
    private long latencyPerSearch(IntUnaryOperator work) {
        for (int i = 0; i < 3; i++) work.applyAsInt(sources[i]);
        var ns = new long[5];
        for (int i = 0; i < ns.length; i++) {
            long t0 = System.nanoTime();
            work.applyAsInt(sources[i]);
            ns[i] = System.nanoTime() - t0;
        }
        return BenchSupport.median(ns);
    }

    private long allocationAt(IntUnaryOperator work) {
        for (int i = 0; i < 3; i++) work.applyAsInt(sources[i]);
        int runs = 5;
        long before = BenchSupport.allocatedBytes();
        for (int i = 0; i < runs; i++) work.applyAsInt(sources[i]);
        return (BenchSupport.allocatedBytes() - before) / runs;
    }

    @AfterAll
    void tearDown() throws IOException {
        try {
            jdbc.execute("DROP TABLE IF EXISTS perf_od_cache");
        } catch (Exception e) {
            System.out.println("perf_od_cache 정리 실패: " + e.getMessage());
        }
        report.write("concurrency.md");
    }

    // ── 1. 같은 답을 내는지 ──────────────────────────────────────────────────

    @Test
    @Order(1)
    void sameAnswers() {
        for (int i = 0; i < 3; i++) {
            int s = sources[i];
            assertThat(cappedViaDbLean(s, HOUR, CAPPED_SETTLED))
                    .as("쓰레기 최소화 DB 방식이 인메모리와 다른 거리를 낸다")
                    .isEqualTo(cappedInMemory(s, HOUR, CAPPED_SETTLED));
            assertThat(cappedViaDb(s, HOUR, CAPPED_SETTLED))
                    .as("DB 간선 조회가 인메모리와 다른 거리를 낸다")
                    .isEqualTo(cappedInMemory(s, HOUR, CAPPED_SETTLED));
        }
        report.line("## 1. 먼저 — 두 방식이 같은 탐색을 하는가");
        report.line("");
        report.line("출발지 3곳에서 노드 %,d 개를 확장한 뒤의 거리 배열이 인메모리와 DB 간선 조회에서 "
                + "완전히 같다. 알고리즘·힙·확장 순서가 같고 **이웃을 어디서 읽는지만** 다르다.",
                CAPPED_SETTLED);
        report.line("");
    }

    // ── 2. 같은 단위작업 — 인메모리 대 DB 간선 조회 ───────────────────────────

    @Test
    @Order(2)
    void cappedSweep() {
        report.line("## 2. 같은 단위작업(노드 %,d 개 확장) — 동시 요청을 올리며", CAPPED_SETTLED);
        report.line("");
        report.line("한 요청 = 출발지 한 곳에서 노드 %,d 개 확장. 두 방식이 **완전히 같은 일**을 한다.",
                CAPPED_SETTLED);
        report.line("");

        var memory = new ArrayList<Level>();
        var db = new ArrayList<Level>();
        for (int c : LEVELS) {
            memory.add(runLevel(c, s -> cappedInMemoryCount(s, HOUR, CAPPED_SETTLED)));
            db.add(runLevel(c, s -> cappedViaDbCount(s, HOUR, CAPPED_SETTLED)));
        }

        sweepTable("응답시간 중앙값", memory, db, l -> BenchSupport.fmtMs(l.median()));
        report.line("");
        sweepTable("응답시간 p95", memory, db, l -> BenchSupport.fmtMs(l.p95()));
        report.line("");
        sweepTable("처리량 (req/s)", memory, db, l -> "%.0f".formatted(l.throughput()));
        report.line("");
        sweepTable("피크 힙 (MB)", memory, db, l -> "%.0f".formatted(l.peakHeapBytes() / 1048576.0));
        report.line("");
        sweepTable("GC 시간 (ms)", memory, db, l -> "%d".formatted(l.gcMillis()));
        report.line("");

        var memMid = lastOk(memory);
        var dbMid = lastOk(db);
        if (memMid != null && dbMid != null) {
            report.line("동시 요청 %d(인메모리) · %d(DB) 에서 DB 간선 조회가 인메모리의 **%.0f배** "
                    + "느리다. 확장 노드 하나당 SQL 왕복 한 번이 붙는 비용이고, 같은 일을 하면서 "
                    + "생기는 차이는 이것뿐이다. 확장당 평균 %s — 이 값으로 §3 의 외삽을 한다.",
                    memMid.concurrency(), dbMid.concurrency(),
                    (double) dbMid.median() / memMid.median(),
                    BenchSupport.fmtMs(dbMid.median() / CAPPED_SETTLED));
            report.line("");
            report.line("§2 는 반복하지 않았다. 배수가 수백 배라 간섭이 몇 배 섞여도 결론이 안 바뀌고, "
                    + "DB 쪽이 측정의 대부분을 차지해 반복하면 시간이 세 배가 된다. 인메모리 쪽 칸은 "
                    + "단위작업이 1~10ms 로 짧아 흔들린다 — 그 칸의 절대값은 믿지 말고 배수만 본다.");
            report.line("");
        }
        // 외삽에는 가장 한가한 수준(동시 1)의 확장당 비용을 쓴다. 동시 100 의 값은 대기가
        // 섞여 있어 DB 쪽에 불리하다 — 불리하게 잡은 숫자로 결론을 내지 않는다.
        var dbLow = firstOk(db);
        var dbHigh = lastOk(db);
        if (dbLow != null) perExpansionNs = (double) dbLow.median() / CAPPED_SETTLED;
        if (dbLow != null && dbHigh != null) {
            report.line("확장당 비용은 동시 %d 에서 %s, 동시 %d 에서 %s 다. 아래 외삽은 **가장 "
                    + "한가한 쪽(%s)** 을 쓴다 — DB 에 유리한 값으로 잡는다.",
                    dbLow.concurrency(), BenchSupport.fmtMs(dbLow.median() / CAPPED_SETTLED),
                    dbHigh.concurrency(), BenchSupport.fmtMs(dbHigh.median() / CAPPED_SETTLED),
                    BenchSupport.fmtMs(dbLow.median() / CAPPED_SETTLED));
            report.line("");
        }
    }

    private double perExpansionNs;

    /** §3 에서 잰 풀의 대가. §5 가 다시 쓴다. 측정 결과로 문장을 만든다 — 미리 쓰지 않는다. */
    private String poolVerdict = "측정되지 않았다.";

    /** 회차 간 차이를 적는다. 차이가 큰 칸은 그 숫자를 덜 믿으라는 뜻이다. */
    private void spreadReport() {
        if (spreads.isEmpty()) return;
        var sorted = new ArrayList<>(spreads);
        sorted.sort((a, b) -> Double.compare(b.ratio(), a.ratio()));
        long noisy = spreads.stream().filter(x -> x.ratio() > 1.5).count();
        var worst = sorted.getFirst();
        report.line("**회차 간 차이.** %d 칸 중 가장 빠른 회차와 가장 느린 회차의 중앙값 차이가 "
                + "1.5배를 넘은 칸은 **%d 칸**이다. 가장 컸던 것은 `%s` · 동시 %d 의 **%.1f배** — "
                + "그 칸은 세 회차 중 적어도 한 번은 간섭을 맞았고, 표에는 가장 빠른 회차가 들어갔다.",
                spreads.size(), noisy, worst.arm(), worst.concurrency(), worst.ratio());
        report.line("");
    }

    /**
     * 풀이 실제로 무엇을 내주는지 — 둘 다 끝까지 돈 가장 높은 수준에서 비교해 문장을 만든다.
     * 첫 하네스는 "p95 가 나빠진다"를 측정 전에 써 두었고, 그 측정은 간섭이 섞여 있었다.
     */
    private String poolTradeoff(List<Level> fresh, List<Level> pooled) {
        Level f = null;
        Level p = null;
        for (int i = fresh.size() - 1; i >= 0; i--) {
            if (!fresh.get(i).failed() && !pooled.get(i).failed()) {
                f = fresh.get(i);
                p = pooled.get(i);
                break;
            }
        }
        if (f == null) return "두 방식이 같이 끝까지 돈 수준이 없어 비교하지 못했다.";

        String heap = "피크 힙 %.0f MB → **%.0f MB**, GC %d ms → **%d ms**"
                .formatted(f.peakHeapBytes() / 1048576.0, p.peakHeapBytes() / 1048576.0,
                        f.gcMillis(), p.gcMillis());
        String median = describe("중앙값", f.median(), p.median());
        String p95 = describe("p95", f.p95(), p.p95());
        double tput = p.throughput() / f.throughput();
        String throughput = Math.abs(tput - 1) < 0.1
                ? "처리량은 비슷하다(%.0f → %.0f req/s)".formatted(f.throughput(), p.throughput())
                : "처리량은 %.0f → %.0f req/s 로 **%.0f%% %s**".formatted(
                        f.throughput(), p.throughput(), Math.abs(tput - 1) * 100, tput > 1 ? "늘었다" : "줄었다");

        return ("동시 %d 에서 `요청마다 할당` 대비 `작업공간 풀 %d벌`: %s. %s, %s, %s. "
                + "풀은 동시 탐색을 %d 개로 묶어 나머지를 기다리게 하는 장치라, 메모리가 상수가 되는 "
                + "대신 기다림이 응답시간 어디에 쌓이는지가 대가다.")
                .formatted(f.concurrency(), PooledSearch.PERMITS, heap, median, p95, throughput,
                        PooledSearch.PERMITS);
    }

    private static String describe(String what, long before, long after) {
        double r = (double) after / before;
        if (Math.abs(r - 1) < 0.1) {
            return "%s 은 비슷하다(%s → %s)".formatted(what, BenchSupport.fmtMs(before), BenchSupport.fmtMs(after));
        }
        return "%s 은 %s → %s 로 **%.1f배 %s**".formatted(what, BenchSupport.fmtMs(before),
                BenchSupport.fmtMs(after), r > 1 ? r : 1 / r, r > 1 ? "나빠졌다" : "좋아졌다");
    }

    /** §3 의 실제 탐색이 확장한 노드 수. §4·§5 가 다시 쓴다. */
    private int fullSearchSettled;

    /** 끝까지 돌아간 마지막 수준. 전부 실패했으면 null. */
    private static Level lastOk(List<Level> levels) {
        Level found = null;
        for (var l : levels) {
            if (!l.failed()) found = l;
        }
        return found;
    }

    /** 끝까지 돌아간 첫 수준. 전부 실패했으면 null. */
    private static Level firstOk(List<Level> levels) {
        for (var l : levels) {
            if (!l.failed()) return l;
        }
        return null;
    }

    // ── 3. 실제 요청 — 행렬 한 줄 ────────────────────────────────────────────

    @Test
    @Order(3)
    void matrixRowSweep() {
        buildPrecomputedTable();

        report.line("## 3. 실제 요청(행렬 한 줄 = 출발지 1곳 → 후보 %d곳) — 동시 요청을 올리며", TARGETS);
        report.line("");
        report.line("§2 의 인공 단위를 실제 요청으로 바꿨다. 조기 종료가 걸리는 진짜 탐색이다.");
        report.line("");
        report.line("**칸마다 %d 회 반복해 중앙값이 가장 작은 회차를 썼다.** 같은 머신의 다른 "
                + "프로세스는 측정을 느리게만 만들 수 있으므로, 가장 빠른 회차가 방해가 가장 적었던 "
                + "상태에 가장 가깝다. 한 칸의 숫자(중앙값·p95·처리량·힙)는 모두 그 한 회차에서 "
                + "나왔다. 요청 수는 수준마다 `max(동시수 × %d, %d)` 다.",
                REPEATS, REQUEST_FACTOR, MIN_REQUESTS);
        report.line("");

        var fresh = new ArrayList<Level>();
        var reused = new ArrayList<Level>();
        var pooled = new ArrayList<Level>();
        var cached = new ArrayList<Level>();
        for (int c : LEVELS) {
            fresh.add(runLevelBest("요청마다 할당", c, s -> Dijkstra.run(graph, s, targets, HOUR).settled()));
            reused.add(runLevelBest("스레드별 재사용", c, s -> ThreadLocalSearch.run(graph, s, targets, HOUR)));
            pooled.add(runLevelBest("작업공간 풀", c, s -> PooledSearch.run(graph, s, targets, HOUR)));
            cached.add(runLevelBest("사전계산 조회", c, this::matrixRowFromCache));
        }

        var fullSearchSettled = Dijkstra.run(graph, sources[0], targets, HOUR).settled();

        report.line("### 응답시간 중앙값");
        report.line("");
        sweepRows(fresh, reused, pooled, cached, l -> BenchSupport.fmtMs(l.median()));
        report.line("");
        report.line("### 응답시간 p95");
        report.line("");
        sweepRows(fresh, reused, pooled, cached, l -> BenchSupport.fmtMs(l.p95()));
        report.line("");
        report.line("### 처리량 (req/s)");
        report.line("");
        sweepRows(fresh, reused, pooled, cached, l -> "%.0f".formatted(l.throughput()));
        report.line("");
        report.line("### 피크 힙 (MB)");
        report.line("");
        sweepRows(fresh, reused, pooled, cached, l -> "%.0f".formatted(l.peakHeapBytes() / 1048576.0));
        report.line("");
        report.line("### GC 시간 (ms)");
        report.line("");
        sweepRows(fresh, reused, pooled, cached, l -> "%d".formatted(l.gcMillis()));
        report.line("");

        var freshAt = firstOk(fresh);
        var cachedAt = firstOk(cached);
        double dbSeconds = perExpansionNs * fullSearchSettled / 1e9;

        report.line("**DB 간선 조회는 이 표에 넣지 못했다.** 탐색 한 번에 노드 %,d 개를 확장하므로 "
                + "§2 의 확장당 %s 로 환산하면 요청 하나에 **약 %.1f 초**다. 동시 요청 1 로도 "
                + "한 칸을 재는 데 수 시간이 걸려 외삽만 적는다.",
                fullSearchSettled, BenchSupport.fmtMs(Math.round(perExpansionNs)), dbSeconds);
        report.line("");
        report.line("| 방식 | 요청 하나 | 비고 |");
        report.line("|---|---|---|");
        if (freshAt != null) {
            report.line("| 인메모리 CSR | %s | 실측 (동시 %d) |",
                    BenchSupport.fmtMs(freshAt.median()), freshAt.concurrency());
        }
        report.line("| DB 간선 조회 | 약 %.1f s | **외삽** — 확장 %,d회 × %s |",
                dbSeconds, fullSearchSettled, BenchSupport.fmtMs(Math.round(perExpansionNs)));
        if (cachedAt != null) {
            report.line("| 사전계산 SQL 조회 | %s | 실측 (동시 %d) · 답이 이미 표에 있을 때만 |",
                    BenchSupport.fmtMs(cachedAt.median()), cachedAt.concurrency());
        }
        report.line("");
        spreadReport();
        poolVerdict = poolTradeoff(fresh, pooled);
        report.line("**작업공간 풀의 거래.** %s", poolVerdict);
        report.line("");
        this.fullSearchSettled = fullSearchSettled;
    }

    // ── 4. 사전계산의 저장 비용 ──────────────────────────────────────────────

    @Test
    @Order(4)
    void precomputeCost() {
        int walkNodes = 0;
        int stopNodes = 0;
        for (int n = 0; n < graph.nodeCount(); n++) {
            if (graph.kindOf(n) == NodeKind.WALK) walkNodes++;
            else if (graph.kindOf(n) == NodeKind.STOP) stopNodes++;
        }
        int snappable = walkNodes + stopNodes;

        // 행 하나: source BIGINT + target BIGINT + hour SMALLINT + seconds INTEGER
        // + 행 헤더 23B + 정렬. 인덱스는 별도. 넉넉하지 않게 48B 로 잡는다.
        long bytesPerRow = 48;
        double fullRows = (double) snappable * snappable * 24;
        double stopRows = (double) stopNodes * stopNodes * 24;

        report.line("## 4. 사전계산은 왜 못 쓰나 — 저장 비용");
        report.line("");
        report.line("§3 에서 사전계산 조회가 가장 빨랐다. 답이 이미 표에 있으면 인덱스 조회 한 번이니 "
                + "당연하다. 문제는 **그 표를 만들 수 있느냐**다. 출발지는 사용자가 찍는 임의 좌표라 "
                + "스냅 대상 노드 전부가 출발지가 될 수 있다.");
        report.line("");
        report.line("| 항목 | 값 |");
        report.line("|---|---|");
        report.line("| 스냅 대상 노드 (WALK %,d + STOP %,d) | **%,d** |", walkNodes, stopNodes, snappable);
        report.line("| 전면 사전계산 행 수 (노드² × 24시간대) | **%.2e 행** |", fullRows);
        report.line("| 행 48B 가정 시 용량 | **%.0f TB** |", fullRows * bytesPerRow / 1e12);
        report.line("| 역·정류장끼리만(STOP² × 24) | %.2e 행 · %.0f GB |",
                stopRows, stopRows * bytesPerRow / 1e9);
        report.line("| 지금 그래프 전체 | %.1f MB |", graphBytes / 1048576.0);
        report.line("");
        report.line("전면 사전계산은 TB 급이라 만들 수 없다. 역·정류장끼리만 미리 계산하는 쪽은 "
                + "크기가 들어오지만, **출발지와 후보는 임의 좌표라 양쪽 끝의 보행 구간이 남는다** — "
                + "그 부분은 결국 탐색해야 하므로 인메모리 그래프가 그대로 필요하다. "
                + "사전계산은 인메모리를 **대체**하지 못하고 그 위에 얹는 캐시가 된다.");
        report.line("");
        report.line("## 5. 그래서");
        report.line("");
        report.line("- 인메모리로 **사는 것**은 SQL 실행 시간이 아니다. 탐색 1회가 노드 %,d 개를 "
                + "확장하므로, 이웃을 DB 에서 읽으면 왕복이 그만큼 붙는다 — **질의 1번 대 %,d번**의 "
                + "차이다. 이게 CRUD 와 경로탐색을 가르는 지점이다.",
                fullSearchSettled, fullSearchSettled);
        report.line("- 인메모리로 **내주는 것**은 인스턴스마다 붙는 %.0f MB, 콜드스타트, "
                + "그래프 교체 시점에 인스턴스끼리 다른 `build_id` 를 볼 수 있다는 점이다.",
                graphBytes / 1048576.0);
        report.line("- 사용자가 늘 때 위험한 것은 그래프가 아니라 **탐색 작업공간**이다. "
                + "스레드별 재사용은 GC 만 없애고 상한은 그대로 둔다(스레드마다 한 벌). "
                + "**작업공간 풀 %d벌**이 그 비례를 상수로 바꾼다 — §3 의 피크 힙 열이 그 차이다.",
                PooledSearch.PERMITS);
        report.line("- 풀의 대가(§3): %s", poolVerdict);
        report.line("- 사전계산 + SQL 조회가 가장 빠르지만 전면 적용은 저장 비용이 막는다. "
                + "**트래픽이 더 늘면 인메모리를 버리는 게 아니라 그 위에 인기 OD 캐시를 얹는다.**");
        report.line("");
    }

    // ── 수준 하나 돌리기 ────────────────────────────────────────────────────

    /**
     * @param median 응답시간 중앙값(ns)
     * @param p95 응답시간 p95(ns)
     * @param throughput 초당 완료 요청 수
     * @param peakHeapBytes 측정 중 힙 사용량 최대치
     * @param gcMillis 측정 구간의 GC 시간
     */
    record Level(int concurrency, long median, long p95, double throughput, long peakHeapBytes,
            long gcMillis, String failure) {

        boolean failed() {
            return failure != null;
        }
    }

    /**
     * 동시 요청 {@code concurrency} 개로 {@code max(concurrency × REQUEST_FACTOR, MIN_REQUESTS)} 번 보낸다.
     *
     * <p>터지는 것도 결과다. 메모리가 모자라 끝내지 못하면 수준을 실패로 적고 다음으로 간다 —
     * 측정이 거기서 멈추면 "어디서 무너지나"를 보고서에 남길 수 없다.
     */
    private Level runLevel(int concurrency, IntUnaryOperator work) {
        // 예열 — JIT 과 커넥션 풀을 데운다. 재지 않는다.
        for (int i = 0; i < WARMUP_REQUESTS; i++) work.applyAsInt(sources[i]);

        int requests = Math.max(concurrency * REQUEST_FACTOR, MIN_REQUESTS);
        var latencies = new long[requests];
        BenchSupport.usedHeapAfterGc();
        var gcBefore = BenchSupport.Gc.now();

        try (var watcher = new BenchSupport.HeapWatcher();
                var pool = Executors.newFixedThreadPool(concurrency)) {
            var tasks = new ArrayList<Callable<Void>>(requests);
            for (int i = 0; i < requests; i++) {
                int at = i;
                int source = sources[WARMUP_REQUESTS + (i % (sources.length - WARMUP_REQUESTS))];
                tasks.add(() -> {
                    long t0 = System.nanoTime();
                    work.applyAsInt(source);
                    latencies[at] = System.nanoTime() - t0;
                    return null;
                });
            }
            long start = System.nanoTime();
            try {
                List<Future<Void>> futures = pool.invokeAll(tasks);
                for (var f : futures) f.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return failedLevel(concurrency, "중단됨");
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                return failedLevel(concurrency,
                        cause instanceof OutOfMemoryError ? "힙 부족" : cause.getClass().getSimpleName());
            } catch (OutOfMemoryError e) {
                return failedLevel(concurrency, "힙 부족");
            }
            long wallNs = System.nanoTime() - start;
            return new Level(
                    concurrency,
                    BenchSupport.median(latencies),
                    BenchSupport.percentile(latencies, 0.95),
                    requests / (wallNs / 1e9),
                    watcher.peakBytes(),
                    BenchSupport.Gc.now().since(gcBefore).millis(),
                    null);
        }
    }

    /**
     * 같은 칸을 {@link #REPEATS} 번 재서 중앙값이 가장 작은 회차를 돌려준다. 중앙값·p95·처리량·
     * 힙이 <b>모두 그 한 회차에서</b> 나온다 — 회차를 섞으면 서로 다른 상태를 이어 붙인 숫자가 된다.
     */
    private Level runLevelBest(String arm, int concurrency, IntUnaryOperator work) {
        Level best = null;
        long fastest = Long.MAX_VALUE;
        long slowest = 0;
        for (int r = 0; r < REPEATS; r++) {
            var l = runLevel(concurrency, work);
            if (l.failed()) continue;
            fastest = Math.min(fastest, l.median());
            slowest = Math.max(slowest, l.median());
            if (best == null || l.median() < best.median()) best = l;
        }
        if (best == null) return failedLevel(concurrency, "세 회차 모두 실패");
        spreads.add(new Spread(arm, concurrency, (double) slowest / fastest));
        return best;
    }

    /** 한 칸의 회차 간 차이 — 가장 느린 회차의 중앙값 ÷ 가장 빠른 회차의 중앙값. */
    record Spread(String arm, int concurrency, double ratio) {}

    private final List<Spread> spreads = new ArrayList<>();

    private static Level failedLevel(int concurrency, String why) {
        System.out.printf("동시 %d 수준이 끝나지 못했다: %s%n", concurrency, why);
        return new Level(concurrency, 0, 0, 0, 0, 0, why);
    }

    // ── 탐색 셋 ─────────────────────────────────────────────────────────────

    /** 인메모리 CSR 로 노드 {@code maxSettled} 개까지 확장. {@link Dijkstra#run} 과 같은 배열을 잡는다. */
    private int[] cappedInMemory(int source, int hour, int maxSettled) {
        int n = graph.nodeCount();
        var dist = new int[n];
        var prevNode = new int[n];
        var prevEdge = new int[n];
        Arrays.fill(dist, Dijkstra.UNREACHED);
        Arrays.fill(prevNode, -1);
        Arrays.fill(prevEdge, -1);

        var queue = new LongMinHeap(1024);
        dist[source] = 0;
        queue.push(LongMinHeap.pack(0, source));

        int settled = 0;
        while (!queue.isEmpty() && settled < maxSettled) {
            long top = queue.pop();
            int u = LongMinHeap.nodeOf(top);
            int d = LongMinHeap.distanceOf(top);
            if (d > dist[u]) continue;
            settled++;

            int end = graph.edgeEnd(u);
            for (int e = graph.edgeStart(u); e < end; e++) {
                int v = graph.edgeTarget(e);
                int w = graph.weight(e, hour);
                if (w < 0) continue;
                int nd = d + w;
                if (nd < dist[v]) {
                    dist[v] = nd;
                    prevNode[v] = u;
                    prevEdge[v] = e;
                    queue.push(LongMinHeap.pack(nd, v));
                }
            }
        }
        return dist;
    }

    private int cappedInMemoryCount(int source, int hour, int maxSettled) {
        return cappedInMemory(source, hour, maxSettled).length;
    }

    /**
     * 같은 탐색인데 <b>이웃을 매번 SQL 로</b> 읽는다. 확장 노드 하나당 질의 한 번이다.
     *
     * <p>질의는 인덱스 {@code ix_graph_edge_from (build_id, from_node_id)} 를 탄다. 시간대
     * 가중치도 DB 에서 골라 보내 클라이언트 쪽 배열 변환 비용을 없앴다 — DB 쪽에 유리하게 잡은
     * 대조군이다.
     */
    private int[] cappedViaDb(int source, int hour, int maxSettled) {
        int n = graph.nodeCount();
        var dist = new int[n];
        var prevNode = new int[n];
        var prevEdge = new int[n];
        Arrays.fill(dist, Dijkstra.UNREACHED);
        Arrays.fill(prevNode, -1);
        Arrays.fill(prevEdge, -1);

        var queue = new LongMinHeap(1024);
        dist[source] = 0;
        queue.push(LongMinHeap.pack(0, source));

        int settled = 0;
        while (!queue.isEmpty() && settled < maxSettled) {
            long top = queue.pop();
            int u = LongMinHeap.nodeOf(top);
            int d = LongMinHeap.distanceOf(top);
            if (d > dist[u]) continue;
            settled++;

            for (var e : neighborsFromDb(graph.dbIdOf(u), hour)) {
                int v = graph.indexOf(e.toDbId());
                if (v < 0 || e.weight() < 0) continue;
                int nd = d + e.weight();
                if (nd < dist[v]) {
                    dist[v] = nd;
                    prevNode[v] = u;
                    prevEdge[v] = -1;   // DB 쪽은 엣지 첨자가 없다. 역추적에는 쓰지 않는다
                    queue.push(LongMinHeap.pack(nd, v));
                }
            }
        }
        return dist;
    }

    private int cappedViaDbCount(int source, int hour, int maxSettled) {
        return cappedViaDb(source, hour, maxSettled).length;
    }

    record DbEdge(long toDbId, int weight) {}

    private static final String NEIGHBORS = """
            SELECT to_node_id, COALESCE(weight_by_hour[?], weight_sec) AS w
            FROM graph_edge
            WHERE build_id = ? AND from_node_id = ?
            """;

    /**
     * 쓰레기를 최소로 줄인 DB 방식. 같은 탐색·같은 질의인데 <b>객체를 거의 만들지 않는다</b>.
     *
     * <p>{@link #cappedViaDb} 와 무엇이 다른가:
     *
     * <ul>
     *   <li>커넥션과 {@code PreparedStatement} 를 <b>탐색 하나 동안 한 번만</b> 만들어 재사용한다.
     *       질의마다 풀에서 빌리고 돌려주는 비용도, 구문을 다시 준비하는 비용도 없다.
     *       pgjdbc 가 서버측 준비 구문으로 승격하므로 파싱·플랜도 사라진다
     *   <li>결과를 {@code List} 나 레코드로 옮기지 않고 {@code ResultSet} 에서 <b>바로 완화한다</b>
     *   <li>파라미터를 {@code setLong}/{@code setInt} 로 넣어 박싱이 없다
     *       ({@code JdbcTemplate} 의 가변인자 {@code Object...} 는 {@code Long}·{@code Integer}
     *       를 만든다)
     * </ul>
     *
     * <p>그래도 질의마다 드라이버가 {@code ResultSet} 과 와이어 프로토콜 버퍼를 만든다.
     * 그게 애플리케이션 코드로 없앨 수 없는 바닥이고, §0 에서 그 바닥을 잰다.
     *
     * <p>대가가 하나 있다. 커넥션을 탐색이 끝날 때까지 쥐고 있으므로 <b>동시 탐색 수가 커넥션
     * 풀 크기에 묶인다</b>. 풀 {@value #POOL_SIZE} 면 동시 탐색 {@value #POOL_SIZE} 개가 상한이다.
     */
    private int[] cappedViaDbLean(int source, int hour, int maxSettled) {
        int n = graph.nodeCount();
        var dist = new int[n];
        var prevNode = new int[n];
        var prevEdge = new int[n];
        Arrays.fill(dist, Dijkstra.UNREACHED);
        Arrays.fill(prevNode, -1);
        Arrays.fill(prevEdge, -1);

        var queue = new LongMinHeap(1024);
        dist[source] = 0;
        queue.push(LongMinHeap.pack(0, source));

        try (var conn = dataSource.getConnection();
                var ps = conn.prepareStatement(NEIGHBORS)) {
            ps.setInt(1, hour + 1);          // PostgreSQL 배열은 1-기반
            ps.setLong(2, graph.buildId());

            int settled = 0;
            while (!queue.isEmpty() && settled < maxSettled) {
                long top = queue.pop();
                int u = LongMinHeap.nodeOf(top);
                int d = LongMinHeap.distanceOf(top);
                if (d > dist[u]) continue;
                settled++;

                ps.setLong(3, graph.dbIdOf(u));
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int w = rs.getInt(2);
                        if (w < 0) continue;
                        int v = graph.indexOf(rs.getLong(1));
                        if (v < 0) continue;
                        int nd = d + w;
                        if (nd < dist[v]) {
                            dist[v] = nd;
                            prevNode[v] = u;
                            prevEdge[v] = -1;
                            queue.push(LongMinHeap.pack(nd, v));
                        }
                    }
                }
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("DB 탐색이 실패했다", e);
        }
        return dist;
    }

    private int cappedViaDbLeanCount(int source, int hour, int maxSettled) {
        return cappedViaDbLean(source, hour, maxSettled).length;
    }

    private List<DbEdge> neighborsFromDb(long fromDbId, int hour) {
        // PostgreSQL 배열은 1-기반이다.
        return jdbc.query(NEIGHBORS,
                (rs, i) -> new DbEdge(rs.getLong(1), rs.getInt(2)),
                hour + 1, graph.buildId(), fromDbId);
    }

    /** 사전계산 표에서 답을 읽는다. 요청당 인덱스 조회 한 번. */
    private int matrixRowFromCache(int source) {
        var rows = jdbc.query(
                "SELECT target_node, seconds FROM perf_od_cache WHERE source_node = ? AND hour = ?",
                (rs, i) -> rs.getInt(2), graph.dbIdOf(source), HOUR);
        return rows.size();
    }

    /**
     * 사전계산 표를 만든다. 표본 출발지에 대해서만 — 전면 사전계산이 불가능한 이유는 §4 에서 센다.
     */
    private void buildPrecomputedTable() {
        jdbc.execute("DROP TABLE IF EXISTS perf_od_cache");
        jdbc.execute("""
                CREATE TABLE perf_od_cache (
                    source_node BIGINT   NOT NULL,
                    target_node BIGINT   NOT NULL,
                    hour        SMALLINT NOT NULL,
                    seconds     INTEGER  NOT NULL,
                    PRIMARY KEY (source_node, hour, target_node)
                )
                """);

        var batch = new ArrayList<Object[]>();
        for (int source : sources) {
            var result = Dijkstra.run(graph, source, targets, HOUR);
            for (int t : targets) {
                if (!result.reached(t)) continue;
                batch.add(new Object[] {graph.dbIdOf(source), graph.dbIdOf(t), HOUR, result.dist()[t]});
            }
        }
        jdbc.batchUpdate(
                "INSERT INTO perf_od_cache VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING", batch);
        jdbc.execute("ANALYZE perf_od_cache");

        Integer rows = jdbc.queryForObject("SELECT count(*) FROM perf_od_cache", Integer.class);
        assertThat(rows).isNotNull().isPositive();
    }

    /**
     * 탐색 작업공간 한 벌. 알고리즘은 {@link Dijkstra#run} 과 같고 <b>배열을 새로 잡지 않는다</b>.
     *
     * <p>요청마다 {@code int[213674]} 셋을 새로 잡으면 요청당 2.4MB 가 GC 로 흘러간다.
     * 한 벌을 들고 매 호출에 {@code Arrays.fill} 로 지우면 쓰레기가 0 이 된다 — 지우는 비용은
     * 잡는 비용과 비슷하지만, 할당과 수거가 사라진다.
     */
    static final class Scratch {

        private final int[] dist;
        private final int[] prevNode;
        private final int[] prevEdge;
        private final boolean[] wanted;
        private final LongMinHeap queue;

        Scratch(int n) {
            this.dist = new int[n];
            this.prevNode = new int[n];
            this.prevEdge = new int[n];
            this.wanted = new boolean[n];
            this.queue = new LongMinHeap(4096);
        }

        int size() {
            return dist.length;
        }

        int search(TransitGraph graph, int source, int[] targets, int hour) {
            Arrays.fill(dist, Dijkstra.UNREACHED);
            Arrays.fill(prevNode, -1);
            Arrays.fill(prevEdge, -1);
            Arrays.fill(wanted, false);

            int remaining = 0;
            for (int t : targets) {
                if (t >= 0 && !wanted[t]) {
                    wanted[t] = true;
                    remaining++;
                }
            }

            // 힙은 비워서 다시 쓴다 — pop 으로 비우는 게 새로 잡는 것보다 싸다.
            while (!queue.isEmpty()) queue.pop();
            dist[source] = 0;
            queue.push(LongMinHeap.pack(0, source));

            int settled = 0;
            while (!queue.isEmpty()) {
                long top = queue.pop();
                int u = LongMinHeap.nodeOf(top);
                int d = LongMinHeap.distanceOf(top);
                if (d > dist[u]) continue;
                settled++;

                if (wanted[u]) {
                    wanted[u] = false;
                    if (--remaining == 0) break;
                }

                int end = graph.edgeEnd(u);
                for (int e = graph.edgeStart(u); e < end; e++) {
                    int v = graph.edgeTarget(e);
                    int w = graph.weight(e, hour);
                    if (w < 0) continue;
                    int nd = d + w;
                    if (nd < dist[v]) {
                        dist[v] = nd;
                        prevNode[v] = u;
                        prevEdge[v] = e;
                        queue.push(LongMinHeap.pack(nd, v));
                    }
                }
            }
            return settled;
        }
    }

    /**
     * 작업공간을 <b>스레드마다</b> 한 벌 들고 재사용한다.
     *
     * <p>쓰레기는 없어지지만 <b>메모리는 여전히 선형</b>이다 — 스레드가 100 개면 한 벌씩 100 벌이
     * 남는다. 톰캣 기본 스레드 200 개면 그것만 490MB 다. GC 는 줄고 상한은 그대로다.
     */
    static final class ThreadLocalSearch {

        private static final ThreadLocal<Scratch> LOCAL = new ThreadLocal<>();

        static int run(TransitGraph graph, int source, int[] targets, int hour) {
            var self = LOCAL.get();
            if (self == null || self.size() != graph.nodeCount()) {
                self = new Scratch(graph.nodeCount());
                LOCAL.set(self);
            }
            return self.search(graph, source, targets, hour);
        }
    }

    /**
     * 작업공간 <b>풀</b>. 한 벌을 빌려 쓰고 돌려준다. 벌 수가 곧 동시 탐색 수의 상한이다.
     *
     * <p>이게 "사용자가 늘면 터지지 않나" 에 대한 실제 답이다. 벌 수를 코어 수로 묶으면
     * <b>동시 요청이 10 이든 100 이든 작업공간 메모리가 같다</b>. 요청이 벌을 기다리는 동안
     * 막히지만, 탐색은 CPU 바운드라 코어 수보다 많이 돌려도 어차피 빨라지지 않는다 —
     * 메모리를 쓰고도 못 얻는 처리량이었다는 뜻이다.
     */
    static final class PooledSearch {

        /** 동시 탐색 수의 상한. 코어 수로 둔다. */
        static final int PERMITS = Runtime.getRuntime().availableProcessors();

        private static volatile ArrayBlockingQueue<Scratch> pool;

        /**
         * 풀이 어느 노드 수로 만들어졌는지. <b>큐를 들여다봐서 판정하면 안 된다</b> — 16벌이
         * 모두 나가 있는 순간 큐가 비고, 그걸 "아직 안 만들었다"로 읽어 풀을 통째로 다시 만든다.
         * 첫 측정에서 그 버그로 풀 쪽 GC 가 되레 늘었다.
         */
        private static volatile int poolNodeCount = -1;

        private static ArrayBlockingQueue<Scratch> ensure(int n) {
            if (poolNodeCount == n) return pool;
            synchronized (PooledSearch.class) {
                if (poolNodeCount != n) {
                    var fresh = new ArrayBlockingQueue<Scratch>(PERMITS);
                    for (int i = 0; i < PERMITS; i++) fresh.add(new Scratch(n));
                    pool = fresh;
                    poolNodeCount = n;
                }
                return pool;
            }
        }

        static int run(TransitGraph graph, int source, int[] targets, int hour) {
            var p = ensure(graph.nodeCount());
            Scratch scratch;
            try {
                scratch = p.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("작업공간을 기다리다 중단됐다", e);
            }
            try {
                return scratch.search(graph, source, targets, hour);
            } finally {
                p.add(scratch);
            }
        }
    }

    // ── 표 ──────────────────────────────────────────────────────────────────

    private void sweepTable(String title, List<Level> memory, List<Level> db,
            java.util.function.Function<Level, String> cell) {
        report.line("**%s**", title);
        report.line("");
        var header = new StringBuilder("| 방식 |");
        var divider = new StringBuilder("|---|");
        for (int c : LEVELS) {
            header.append(' ').append(c).append(" |");
            divider.append("---|");
        }
        report.line(header.toString());
        report.line(divider.toString());
        report.line(rowOf("인메모리 CSR", memory, cell));
        report.line(rowOf("DB 간선 조회", db, cell));
    }

    private void sweepRows(List<Level> fresh, List<Level> reused, List<Level> pooled,
            List<Level> cached, java.util.function.Function<Level, String> cell) {
        var header = new StringBuilder("| 방식 |");
        var divider = new StringBuilder("|---|");
        for (int c : LEVELS) {
            header.append(' ').append(c).append(" |");
            divider.append("---|");
        }
        report.line(header.toString());
        report.line(divider.toString());
        report.line(rowOf("인메모리 — 요청마다 할당 (지금)", fresh, cell));
        report.line(rowOf("인메모리 — 스레드별 재사용", reused, cell));
        report.line(rowOf("인메모리 — 작업공간 풀 %d벌".formatted(PooledSearch.PERMITS), pooled, cell));
        report.line(rowOf("사전계산 SQL 조회", cached, cell));
    }

    private String rowOf(String label, List<Level> levels,
            java.util.function.Function<Level, String> cell) {
        var out = new StringBuilder("| ").append(label).append(" |");
        for (var l : levels) {
            out.append(' ').append(l.failed() ? "**" + l.failure() + "**" : cell.apply(l)).append(" |");
        }
        return out.toString();
    }
}
