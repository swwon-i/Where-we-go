package com.wherewego.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.wherewego.graph.GraphLoader;
import com.wherewego.graph.TransitGraph;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.StringJoiner;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.function.IntUnaryOperator;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
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
 * pgRouting 대조. "PostGIS 를 쓰면서 왜 pgRouting 을 안 썼나" 에 숫자로 답한다.
 *
 * <p>{@link ConcurrencySweepTest} 의 DB 대조군("확장 노드마다 SQL 1번")은 아무도 그렇게 짜지
 * 않는 방식이라 허수아비라는 지적을 받았다. 실무에서 "DB 로 경로를 찾는다" 고 할 때 실제로 쓰는
 * 것이 pgRouting 이다 — <b>탐색 자체를 DB 안에서</b> C++(Boost Graph Library)로 돌린다.
 *
 * <p>세 가지를 잰다.
 *
 * <ol>
 *   <li><b>인메모리 CSR</b> — 지금 쓰는 것
 *   <li><b>pgRouting · 원본 표</b> — {@code graph_edge} 에서 시간대 가중치를 골라 바로 넘긴다
 *   <li><b>pgRouting · 뽑아 둔 표</b> — 이 시간대의 간선만 미리 별도 표로 뽑아 둔다. 배열
 *       인덱싱·필터가 빠지므로 <b>pgRouting 에 유리하게</b> 잡은 쪽이다
 * </ol>
 *
 * <p>그리고 3번을 <b>그래프 짓기</b>와 <b>탐색</b>으로 쪼갠다. pgRouting 은 호출마다 간선 SQL 을
 * 실행해 결과를 받고 Boost 그래프를 새로 짓는다. 출발지와 도착지를 같은 노드로 주면 탐색이
 * 사실상 0 이 되어 짓는 비용만 남는다.
 *
 * <h2>설치</h2>
 *
 * {@code postgis/postgis:17-3.5} 이미지에는 pgRouting 이 없다. 측정할 때만 컨테이너에 넣는다:
 *
 * <pre>
 *   docker exec wwg-db bash -c "echo 'deb [ signed-by=/usr/local/share/keyrings/postgres.gpg.asc ] \
 *     http://apt-archive.postgresql.org/pub/repos/apt/ bullseye-pgdg-archive main' \
 *     > /etc/apt/sources.list.d/pgdg.list && apt-get update && \
 *     apt-get install -y --no-install-recommends postgresql-17-pgrouting"
 * </pre>
 *
 * 컨테이너를 다시 만들면 사라진다 — 운영 이미지에는 넣지 않는다. 확장이 없으면 이 측정은 건너뛴다.
 * 측정이 만든 확장과 표는 끝날 때 지운다.
 *
 * <p>결과는 {@code build/reports/perf/pgrouting.md}. {@code ./gradlew pgroutingTest}.
 */
@Tag("perf")
@SpringBootTest(properties = {
    "spring.datasource.hikari.maximum-pool-size=30",
    "spring.datasource.hikari.connection-timeout=120000",
})
@EnabledIf("databaseIsUp")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PgRoutingCompareTest {

    static boolean databaseIsUp() {
        return BenchSupport.databaseIsUp();
    }

    private static final int HOUR = 19;
    private static final long SEED = 42;
    private static final int TARGETS = 20;
    /**
     * 동시 수준. <b>코어 수까지만</b> 올린다.
     *
     * <p>pgRouting 은 호출마다 DB 프로세스 안에 간선 60만 개짜리 그래프를 짓는다. 동시 N 이면
     * N 벌이다. 개발 머신(16 코어, 메모리 넉넉)에서는 1·2·4·16 이 다 돌지만, 운영 서버(vCPU 2,
     * 2GB)에서 16 벌을 지으면 DB 가 메모리 부족으로 죽을 수 있다. 코어 수를 넘겨 올려 봐야
     * CPU 바운드라 처리량도 더 안 나오므로, 코어 수에서 멈춰도 잃는 것이 없다.
     */
    private static final int[] LEVELS = java.util.stream.IntStream.of(1, 2, 4, 16)
            .filter(c -> c <= Runtime.getRuntime().availableProcessors())
            .toArray();
    private static final int REPEATS = 3;
    private static final int MIN_REQUESTS = 10;
    private static final int WARMUP = 2;

    @Autowired GraphLoader loader;
    @Autowired DataSource dataSource;

    private JdbcTemplate jdbc;
    private final BenchSupport.Report report = new BenchSupport.Report();

    private TransitGraph graph;
    private int[] sources;
    private int[] targets;
    private String targetArray;   // '{id,id,...}' — 후보의 DB id
    private boolean createdExtension;

    /** 원본 표에서 이 시간대의 간선을 고르는 SQL. pgRouting 이 호출마다 실행한다. */
    private String rawEdgesSql;

    /** 미리 뽑아 둔 표에서 그대로 읽는 SQL. */
    private static final String PREPARED_EDGES_SQL =
            "SELECT id, source, target, cost FROM perf_pgr_edges";

    @BeforeAll
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);

        Boolean had = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pgrouting')", Boolean.class);
        try {
            jdbc.execute("CREATE EXTENSION IF NOT EXISTS pgrouting");
        } catch (Exception e) {
            Assumptions.abort("pgRouting 이 설치되어 있지 않다 — 클래스 주석의 설치 명령을 먼저: " + e.getMessage());
        }
        createdExtension = !Boolean.TRUE.equals(had);

        graph = loader.load();

        // ConcurrencySweepTest 와 같은 씨앗·같은 순서로 뽑는다 → 같은 출발지·같은 후보
        int[] walk = BenchSupport.connectedWalkNodes(graph, HOUR);
        var rng = new Random(SEED);
        sources = BenchSupport.pick(walk, 400, rng);
        targets = BenchSupport.pick(walk, TARGETS, rng);

        var ids = new StringJoiner(",", "{", "}");
        for (int t : targets) ids.add(Long.toString(graph.dbIdOf(t)));
        targetArray = ids.toString();

        // PostgreSQL 배열은 1-기반. NO_SERVICE(-1) 인 칸은 그 시간대에 없는 간선이다.
        rawEdgesSql = ("SELECT id, from_node_id AS source, to_node_id AS target, "
                + "COALESCE(weight_by_hour[%d], weight_sec)::float8 AS cost FROM graph_edge "
                + "WHERE build_id = %d AND COALESCE(weight_by_hour[%d], weight_sec) >= 0")
                .formatted(HOUR + 1, graph.buildId(), HOUR + 1);

        jdbc.execute("DROP TABLE IF EXISTS perf_pgr_edges");
        jdbc.execute("CREATE UNLOGGED TABLE perf_pgr_edges AS " + rawEdgesSql);
        jdbc.execute("ANALYZE perf_pgr_edges");
        Integer rows = jdbc.queryForObject("SELECT count(*) FROM perf_pgr_edges", Integer.class);

        String version = jdbc.queryForObject("SELECT pgr_version()", String.class);
        report.header("pgRouting 대조 — 탐색을 DB 안에서 돌리면",
                "pgRouting %s · 시간대 %d시 · 씨앗 %d · 후보 %d곳(서울 전역) · 칸마다 %d회 반복, 중앙값 최소 채택"
                        .formatted(version, HOUR, SEED, TARGETS, REPEATS));
        report.line("빌드 #%d · 노드 %,d · 엣지 %,d · 이 시간대에 다니는 간선 %,d",
                graph.buildId(), graph.nodeCount(), graph.edgeCount(), rows);
        report.line("");
        report.line("출발지·후보는 `ConcurrencySweepTest` 와 같은 씨앗·같은 순서로 뽑았다 — 같은 요청이다. "
                + "pgRouting 쪽은 `pgr_dijkstraCost(간선 SQL, 출발지, 후보 배열, directed => true)` 하나로 "
                + "행렬 한 줄을 낸다. 지금 서비스가 하는 일과 같은 모양이다.");
        report.line("");
    }

    @AfterAll
    void tearDown() throws IOException {
        try {
            jdbc.execute("DROP TABLE IF EXISTS perf_pgr_edges");
            if (createdExtension) jdbc.execute("DROP EXTENSION IF EXISTS pgrouting");
        } catch (Exception e) {
            System.out.println("정리 실패: " + e.getMessage());
        }
        report.write("pgrouting.md");
    }

    // ── 0. 같은 답 ──────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void sameAnswers() {
        int pairs = 0;
        for (int i = 0; i < 3; i++) {
            int s = sources[i];
            var ours = Dijkstra.run(graph, s, targets, HOUR);
            var theirs = pgr(rawEdgesSql, graph.dbIdOf(s), targetArray);
            for (int t : targets) {
                Double cost = theirs.get(graph.dbIdOf(t));
                if (!ours.reached(t)) {
                    assertThat(cost).as("우리는 못 닿는데 pgRouting 은 닿았다").isNull();
                    continue;
                }
                assertThat(cost).as("pgRouting 이 후보에 못 닿았다: %d → %d", s, t).isNotNull();
                assertThat(Math.round(cost))
                        .as("소요시간이 다르다: %d → %d", s, t)
                        .isEqualTo((long) ours.dist()[t]);
                pairs++;
            }
        }
        report.line("## 0. 먼저 — 같은 답을 내는가");
        report.line("");
        report.line("출발지 3곳 × 후보 %d곳 중 닿는 %d 쌍에서 pgRouting 의 소요시간이 인메모리 다익스트라와 "
                + "**초 단위까지 같다.** 같은 그래프·같은 시간대 가중치·같은 `운행 없음` 규칙을 쓴다는 확인이다.",
                TARGETS, pairs);
        report.line("");
    }

    // ── 1. 한 번에 얼마나 걸리나 — 짓기와 탐색으로 쪼개서 ────────────────────

    @Test
    @Order(2)
    void singleCall() {
        var memory = best(1, s -> Dijkstra.run(graph, s, targets, HOUR).settled());
        var raw = best(1, s -> pgr(rawEdgesSql, graph.dbIdOf(s), targetArray).size());
        var prepared = best(1, s -> pgr(PREPARED_EDGES_SQL, graph.dbIdOf(s), targetArray).size());
        // 출발지 = 도착지. 탐색은 사실상 0 이고 간선 읽기 + 그래프 짓기만 남는다.
        var buildOnly = best(1, s -> pgr(PREPARED_EDGES_SQL, graph.dbIdOf(s),
                "{" + graph.dbIdOf(s) + "}").size());
        long scanNs = timeEdgesScan();

        long searchNs = Math.max(0, prepared.median() - buildOnly.median());

        report.line("## 1. 한 번에 얼마나 걸리나 — 동시 1");
        report.line("");
        report.line("| 방식 | 행렬 한 줄 (중앙값) | p95 | 인메모리 대비 |");
        report.line("|---|---|---|---|");
        report.line("| 인메모리 CSR (지금) | **%s** | %s | 1× |",
                BenchSupport.fmtMs(memory.median()), BenchSupport.fmtMs(memory.p95()));
        report.line("| pgRouting · 원본 표 | %s | %s | **%.0f×** |",
                BenchSupport.fmtMs(raw.median()), BenchSupport.fmtMs(raw.p95()),
                (double) raw.median() / memory.median());
        report.line("| pgRouting · 뽑아 둔 표 (유리하게) | %s | %s | **%.0f×** |",
                BenchSupport.fmtMs(prepared.median()), BenchSupport.fmtMs(prepared.p95()),
                (double) prepared.median() / memory.median());
        report.line("");
        report.line("### 그 시간이 어디서 나오나");
        report.line("");
        report.line("| 단계 | 시간 | 몫 |");
        report.line("|---|---|---|");
        report.line("| 간선 %s 행을 DB 안에서 훑기만 (`count(*)`) | %s | %.0f%% |",
                "60만", BenchSupport.fmtMs(scanNs), 100.0 * scanNs / prepared.median());
        report.line("| 간선을 받아 **그래프를 짓기** (출발지 = 도착지로 부른 시간) | **%s** | **%.0f%%** |",
                BenchSupport.fmtMs(buildOnly.median()), 100.0 * buildOnly.median() / prepared.median());
        report.line("| **탐색** (뽑아 둔 표 − 짓기) | %s | %.0f%% |",
                BenchSupport.fmtMs(searchNs), 100.0 * searchNs / prepared.median());
        report.line("");
        report.line("탐색만 떼어 보면 %s 로 인메모리(%s)의 약 %.1f배다 — 같은 다익스트라이고 자릿수가 "
                + "같다(짓기 시간을 빼서 낸 값이라 근사다). 시간의 대부분은 **호출마다 간선 60만 행을 받아 "
                + "그래프를 새로 짓는 데** 든다. pgRouting 은 그래프를 호출 사이에 들고 있지 않는다.",
                BenchSupport.fmtMs(searchNs), BenchSupport.fmtMs(memory.median()),
                (double) searchNs / memory.median());
        report.line("");
        report.line("이건 \"요청마다 간선을 질의 1번으로 다 불러오기\"와 **같은 구조**다. 그 일을 앱이 아니라 "
                + "DB 프로세스 안에서 한다는 차이뿐이다. 그리고 그 구조를 쓸 만하게 만드는 수정 — "
                + "지은 그래프를 호출 사이에 들고 있기 — 이 곧 인메모리 CSR 이다.");
        report.line("");
    }

    // ── 2. 동시 요청 ────────────────────────────────────────────────────────

    @Test
    @Order(3)
    void concurrency() {
        var memory = new ArrayList<Run>();
        var prepared = new ArrayList<Run>();
        for (int c : LEVELS) {
            memory.add(best(c, s -> Dijkstra.run(graph, s, targets, HOUR).settled()));
            prepared.add(best(c, s -> pgr(PREPARED_EDGES_SQL, graph.dbIdOf(s), targetArray).size()));
        }

        report.line("## 2. 동시 요청을 올리면");
        report.line("");
        report.line("pgRouting 은 유리하게 잡은 쪽(뽑아 둔 표)만 올렸다. 커넥션 풀 30, DB 와 앱이 같은 "
                + "머신(코어 %d)을 나눠 쓴다.", Runtime.getRuntime().availableProcessors());
        report.line("");
        table("응답시간 중앙값", memory, prepared, r -> BenchSupport.fmtMs(r.median()));
        table("p95", memory, prepared, r -> BenchSupport.fmtMs(r.p95()));
        table("처리량 (req/s)", memory, prepared, r -> "%.1f".formatted(r.throughput()));

        var m = memory.getLast();
        var p = prepared.getLast();
        report.line("동시 %d 에서 처리량 **%.0f 대 %.1f req/s, %.0f배**. pgRouting 은 요청마다 그래프를 "
                + "새로 지으니 그 비용이 요청 수만큼 곱해진다. 인메모리는 그 비용을 서버 생애에 한 번 낸다.",
                m.concurrency(), m.throughput(), p.throughput(), m.throughput() / p.throughput());
        report.line("");
        report.line("측정하지 않은 것: **DB 쪽 메모리.** pgRouting 은 호출마다 DB 백엔드 프로세스 안에 "
                + "간선 60만 개짜리 그래프를 짓는다. 동시 %d 이면 그게 %d 벌이다. 이 하네스는 JVM 만 "
                + "보므로 그 크기를 재지 못했다 — 같은 머신에서 앱과 RAM 을 나눠 쓴다는 점에서 따로 볼 "
                + "가치가 있다.", LEVELS[LEVELS.length - 1], LEVELS[LEVELS.length - 1]);
        report.line("");
        report.line("## 3. 그래서 — pgRouting 을 왜 안 썼나");
        report.line("");
        report.line("- **답은 같다.** 탐색 알고리즘도 같은 다익스트라이고, 탐색만 떼면 자릿수도 같다");
        report.line("- **다른 것은 그래프를 어디에 얼마나 오래 두느냐다.** pgRouting 은 호출마다 간선을 "
                + "받아 그래프를 짓고 버린다. 이 서비스는 같은 그래프로 하루 종일 탐색하므로, 지은 그래프를 "
                + "들고 있는 쪽이 맞다");
        report.line("- pgRouting 이 맞는 자리도 있다: 탐색이 드물거나, 그래프가 요청마다 바뀌거나(필터가 "
                + "요청마다 다름), 앱 코드 없이 SQL 만으로 끝내고 싶을 때. 이 서비스는 셋 다 아니다");
        report.line("");
    }

    // ── 도구 ────────────────────────────────────────────────────────────────

    /** pgr_dijkstraCost 한 번. 도착지 DB id → 소요시간(초). 닿지 못한 후보는 빠진다. */
    private Map<Long, Double> pgr(String edgesSql, long sourceDbId, String endArray) {
        var out = new HashMap<Long, Double>();
        jdbc.query("SELECT end_vid, agg_cost FROM pgr_dijkstraCost(?, ?::bigint, ?::bigint[], directed => true)",
                rs -> {
                    out.put(rs.getLong(1), rs.getDouble(2));
                },
                edgesSql, sourceDbId, endArray);
        return out;
    }

    private long timeEdgesScan() {
        jdbc.queryForObject("SELECT count(*) FROM perf_pgr_edges", Long.class);
        var ns = new long[5];
        for (int i = 0; i < ns.length; i++) {
            long t0 = System.nanoTime();
            jdbc.queryForObject("SELECT count(*) FROM perf_pgr_edges", Long.class);
            ns[i] = System.nanoTime() - t0;
        }
        return BenchSupport.median(ns);
    }

    record Run(int concurrency, long median, long p95, double throughput) {}

    /** {@link #REPEATS} 번 재서 중앙값이 가장 작은 회차. 숫자는 모두 그 한 회차에서 나온다. */
    private Run best(int concurrency, IntUnaryOperator work) {
        for (int i = 0; i < WARMUP; i++) work.applyAsInt(sources[i]);
        Run best = null;
        for (int r = 0; r < REPEATS; r++) {
            var run = once(concurrency, work);
            if (best == null || run.median() < best.median()) best = run;
        }
        return best;
    }

    private Run once(int concurrency, IntUnaryOperator work) {
        int requests = Math.max(concurrency * 3, MIN_REQUESTS);
        var latencies = new long[requests];
        try (var pool = Executors.newFixedThreadPool(concurrency)) {
            var tasks = new ArrayList<Callable<Void>>(requests);
            for (int i = 0; i < requests; i++) {
                int at = i;
                int source = sources[WARMUP + (i % (sources.length - WARMUP))];
                tasks.add(() -> {
                    long t0 = System.nanoTime();
                    work.applyAsInt(source);
                    latencies[at] = System.nanoTime() - t0;
                    return null;
                });
            }
            long start = System.nanoTime();
            for (var f : pool.invokeAll(tasks)) f.get();
            long wall = System.nanoTime() - start;
            return new Run(concurrency, BenchSupport.median(latencies),
                    BenchSupport.percentile(latencies, 0.95), requests / (wall / 1e9));
        } catch (Exception e) {
            throw new IllegalStateException("동시 %d 측정 실패".formatted(concurrency), e);
        }
    }

    private void table(String title, List<Run> memory, List<Run> pgr,
            java.util.function.Function<Run, String> cell) {
        report.line("**%s**", title);
        report.line("");
        var header = new StringBuilder("| 방식 |");
        var divider = new StringBuilder("|---|");
        for (int c : LEVELS) {
            header.append(" 동시 ").append(c).append(" |");
            divider.append("---|");
        }
        report.line(header.toString());
        report.line(divider.toString());
        var a = new StringBuilder("| 인메모리 CSR |");
        for (var r : memory) a.append(' ').append(cell.apply(r)).append(" |");
        var b = new StringBuilder("| pgRouting (뽑아 둔 표) |");
        for (var r : pgr) b.append(' ').append(cell.apply(r)).append(" |");
        report.line(a.toString());
        report.line(b.toString());
        report.line("");
    }
}
