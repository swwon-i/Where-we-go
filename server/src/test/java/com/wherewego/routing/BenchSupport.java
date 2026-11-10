package com.wherewego.routing;

import com.wherewego.graph.NodeKind;
import com.wherewego.graph.TransitGraph;
import com.wherewego.graph.TransitMode;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

/**
 * 측정 보조. 표본 고르기 · 통계 · 힙/GC 읽기 · 보고서 쓰기.
 *
 * <p>{@link PerfMeasureTest} 가 쓰던 조각을 꺼내 여러 측정이 같은 표본·같은 통계로 돌게 한다.
 * 측정끼리 표본이 다르면 숫자를 나란히 놓을 수 없다.
 */
final class BenchSupport {

    private BenchSupport() {}

    /** 측정은 실제 그래프가 든 DB 를 요구한다. 없으면 측정을 건너뛴다. */
    static boolean databaseIsUp() {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 5432), 300);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ── 표본 ────────────────────────────────────────────────────────────────

    /**
     * 본 보행망에 이어진 보행 노드 전부.
     *
     * <p>섬에 떨어진 노드를 표본으로 잡으면 조기 종료가 걸리지 않아 매번 전체를 훑는다 —
     * 실제 요청에서는 좌표 스냅이 그런 노드를 고르지 않는다. 시청역에서 한 번 퍼뜨려
     * 닿는 노드만 쓴다.
     */
    static int[] connectedWalkNodes(TransitGraph graph, int hour) {
        int hub = graph.findStop(TransitMode.SUBWAY, "시청");
        if (hub < 0) throw new IllegalStateException("시청역이 그래프에 없다");
        var reach = Dijkstra.run(graph, hub, null, hour);

        var walk = new ArrayList<Integer>();
        for (int n = 0; n < graph.nodeCount(); n++) {
            if (graph.kindOf(n) == NodeKind.WALK && reach.reached(n)) walk.add(n);
        }
        var out = new int[walk.size()];
        for (int i = 0; i < out.length; i++) out[i] = walk.get(i);
        return out;
    }

    /** {@code pool} 에서 무작위로 {@code count} 개. 씨앗을 고정하면 다시 돌려도 같은 표본이다. */
    static int[] pick(int[] pool, int count, Random rng) {
        var out = new int[count];
        for (int i = 0; i < count; i++) out[i] = pool[rng.nextInt(pool.length)];
        return out;
    }

    /** {@code center} 에서 반경 {@code radiusM} 안의 노드 중에서 {@code count} 개. */
    static int[] pickNear(TransitGraph graph, int[] pool, int center, double radiusM, int count, Random rng) {
        var near = new ArrayList<Integer>();
        for (int n : pool) {
            if (meters(graph, center, n) <= radiusM) near.add(n);
        }
        if (near.isEmpty()) throw new IllegalStateException("반경 %.0fm 안에 노드가 없다".formatted(radiusM));
        var out = new int[count];
        for (int i = 0; i < count; i++) out[i] = near.get(rng.nextInt(near.size()));
        return out;
    }

    /** 두 노드의 직선거리(m). 서울 안이면 등장방형 근사로 충분하다. */
    static double meters(TransitGraph graph, int a, int b) {
        double lat = Math.toRadians((graph.latOf(a) + graph.latOf(b)) / 2);
        double dx = Math.toRadians(graph.lngOf(b) - graph.lngOf(a)) * Math.cos(lat);
        double dy = Math.toRadians(graph.latOf(b) - graph.latOf(a));
        return 6_371_000 * Math.hypot(dx, dy);
    }

    /**
     * 그래프에서 관측되는 가장 빠른 속도(m/s). A* 휴리스틱의 분모다.
     *
     * <p>엣지마다 <b>양 끝의 직선거리 ÷ 비용</b>을 구해 그 최댓값을 쓴다. 이 값으로 나눈
     * 직선거리는 반드시 실제 최단시간 이하다(허용 휴리스틱):
     *
     * <pre>
     *   경로의 실제 비용 = Σ w_i  ≥  Σ (직선_i / maxSpeed)  ≥  직선(출발,도착) / maxSpeed
     * </pre>
     *
     * 가운데는 엣지마다 {@code w_i ≥ 직선_i / maxSpeed} 이기 때문이고, 오른쪽은 삼각부등식이다.
     * 그래서 A* 가 다익스트라와 <b>같은 답</b>을 낸다 — 아래 측정은 그걸 확인한 뒤의 값이다.
     *
     * @param hour 시간대. 그 시간대에 운행하지 않는 엣지는 세지 않는다
     */
    static double maxSpeedMps(TransitGraph graph, int hour) {
        double best = 0;
        for (int u = 0; u < graph.nodeCount(); u++) {
            int end = graph.edgeEnd(u);
            for (int e = graph.edgeStart(u); e < end; e++) {
                int w = graph.weight(e, hour);
                if (w <= 0) continue;   // NO_SERVICE 이거나 0초(ALIGHT)
                double speed = meters(graph, u, graph.edgeTarget(e)) / w;
                if (speed > best) best = speed;
            }
        }
        return best;
    }

    // ── 통계 ────────────────────────────────────────────────────────────────

    static long median(long[] values) {
        var copy = values.clone();
        Arrays.sort(copy);
        return copy[copy.length / 2];
    }

    static long percentile(long[] values, double p) {
        var copy = values.clone();
        Arrays.sort(copy);
        return copy[(int) Math.ceil(p * copy.length) - 1];
    }

    static String fmtMs(long ns) {
        return "%.1f ms".formatted(ns / 1e6);
    }

    // ── 힙과 GC ─────────────────────────────────────────────────────────────

    /** GC 를 세 번 재촉한 뒤의 사용량. 근사값이고, 비교에는 충분하다. */
    static long usedHeapAfterGc() {
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

    static long usedHeap() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    /**
     * 지금 스레드가 태어나서 지금까지 <b>할당한</b> 바이트 수.
     *
     * <p>피크 힙은 GC 시점에 따라 흔들리지만 할당량은 흔들리지 않는다 — 쓰레기를 얼마나
     * 만드는지 재려면 이쪽이 맞다. 두 시점의 차이가 그 구간의 할당량이다.
     */
    static long allocatedBytes() {
        var bean = ManagementFactory.getThreadMXBean();
        if (bean instanceof com.sun.management.ThreadMXBean sun) {
            return sun.getCurrentThreadAllocatedBytes();
        }
        return -1;
    }

    /** GC 누적 횟수·시간. 두 시점의 차이를 보면 그 구간의 GC 부하가 나온다. */
    record Gc(long count, long millis) {

        static Gc now() {
            long count = 0;
            long millis = 0;
            for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
                if (bean.getCollectionCount() > 0) count += bean.getCollectionCount();
                if (bean.getCollectionTime() > 0) millis += bean.getCollectionTime();
            }
            return new Gc(count, millis);
        }

        Gc since(Gc before) {
            return new Gc(count - before.count, millis - before.millis);
        }
    }

    /**
     * 측정하는 동안 힙 사용량의 최대치를 따라간다. 동시 요청이 쥐는 작업공간은 GC 사이에만
     * 보이므로, 끝난 뒤 한 번 재는 것으로는 잡히지 않는다.
     */
    static final class HeapWatcher implements AutoCloseable {

        private final Thread thread;
        private volatile boolean running = true;
        private volatile long peak;

        HeapWatcher() {
            this.peak = usedHeap();
            this.thread = new Thread(() -> {
                while (running) {
                    long used = usedHeap();
                    if (used > peak) peak = used;
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "heap-watcher");
            this.thread.setDaemon(true);
            this.thread.start();
        }

        long peakBytes() {
            return peak;
        }

        @Override
        public void close() {
            running = false;
            thread.interrupt();
        }
    }

    // ── 보고서 ──────────────────────────────────────────────────────────────

    /** 마크다운을 모아 파일로 쓴다. */
    static final class Report {

        private final StringBuilder out = new StringBuilder();

        void line(String format, Object... args) {
            out.append(args.length == 0 ? format : format.formatted(args)).append('\n');
        }

        void header(String title, String extra) {
            var rt = Runtime.getRuntime();
            line("# %s", title);
            line("");
            line("- 측정 시각 %s", java.time.LocalDateTime.now().withNano(0));
            line("- JVM %s · 최대 힙 %dMB · 코어 %d",
                    System.getProperty("java.version"), rt.maxMemory() >> 20, rt.availableProcessors());
            if (extra != null) line("- %s", extra);
            line("");
        }

        void write(String fileName) throws IOException {
            Path path = Path.of("build/reports/perf").resolve(fileName);
            Files.createDirectories(path.getParent());
            Files.writeString(path, out, StandardCharsets.UTF_8);
            System.out.println(out);
            System.out.println("→ " + path.toAbsolutePath());
        }
    }
}
