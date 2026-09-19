package com.wherewego.graph;

import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 올려둔 그래프를 들고 있는 자리.
 *
 * <p>그래프를 요청마다 DB 에서 읽으면 60만 행을 매번 긁는 셈이라 말이 안 된다. 한 번 읽어 두고
 * 계속 쓴다 — 그래프는 빌드 단위로만 바뀌고, 바뀌면 통째로 교체된다.
 *
 * <h2>기동 때 읽지 않는 이유</h2>
 *
 * 그래프가 없어도 장소 검색은 돌아야 한다. ETL 을 아직 안 돌린 상태에서 서버가 아예 안 뜨면
 * "지도는 뜨는데 경로만 안 나온다" 가 "아무것도 안 뜬다" 가 된다. 그래서 <b>처음 필요할 때</b>
 * 읽고, 실패하면 그 요청만 실패한다.
 *
 * <h2>교체</h2>
 *
 * {@code graph}는 {@code volatile}이고 통째로 갈아끼운다. 읽는 쪽은 잠금 없이 보던 그래프를
 * 끝까지 쓰고, 다음 요청부터 새 것을 본다 — 탐색 중에 그래프가 바뀌는 일이 없다.
 * 만드는 쪽만 잠가서 같은 빌드를 두 번 읽지 않게 한다.
 */
@Component
public class GraphCache {

    private static final Logger log = LoggerFactory.getLogger(GraphCache.class);

    private final GraphLoader loader;

    private volatile TransitGraph graph;
    private volatile Instant loadedAt;
    private volatile long loadMillis;

    public GraphCache(GraphLoader loader) {
        this.loader = loader;
    }

    /**
     * 올려둔 그래프. 없으면 이때 읽는다.
     *
     * @throws IllegalStateException 활성 빌드가 없을 때
     */
    public TransitGraph get() {
        var current = graph;
        if (current != null) return current;

        synchronized (this) {
            if (graph != null) return graph;   // 기다리는 동안 누가 올려놨다
            return replace();
        }
    }

    /** 활성 빌드를 다시 읽는다. ETL 이 그래프를 새로 만든 뒤 부르는 자리다. */
    public synchronized TransitGraph reload() {
        return replace();
    }

    /** DB 의 활성 빌드가 지금 들고 있는 것과 다른가. */
    public boolean isStale() {
        var current = graph;
        return current != null && loader.activeBuildId() != current.buildId();
    }

    private TransitGraph replace() {
        long t0 = System.nanoTime();
        var loaded = loader.load();
        long ms = (System.nanoTime() - t0) / 1_000_000;

        graph = loaded;
        loadedAt = Instant.now();
        loadMillis = ms;
        log.info(
                "그래프 빌드 #{} 적재: 노드 {} · 엣지 {} · 약 {}MB · {}ms",
                loaded.buildId(),
                loaded.nodeCount(),
                loaded.edgeCount(),
                loaded.approximateBytes() / (1024 * 1024),
                ms);
        return loaded;
    }

    /** 상태 요약. {@code /api/v1/graph} 가 쓴다. */
    public Status status() {
        var current = graph;
        if (current == null) {
            return new Status(false, loader.activeBuildId(), 0, 0, 0, null, 0, false);
        }
        return new Status(
                true,
                current.buildId(),
                current.nodeCount(),
                current.edgeCount(),
                current.approximateBytes(),
                loadedAt,
                loadMillis,
                isStale());
    }

    /**
     * @param stale DB 의 활성 빌드가 바뀌었는가. 참이면 {@code reload} 가 필요하다
     */
    public record Status(
            boolean loaded,
            long buildId,
            int nodes,
            int edges,
            long approximateBytes,
            Instant loadedAt,
            long loadMillis,
            boolean stale) {}

    Duration age() {
        var at = loadedAt;
        return at == null ? Duration.ZERO : Duration.between(at, Instant.now());
    }
}
