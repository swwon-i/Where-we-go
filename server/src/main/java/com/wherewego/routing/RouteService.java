package com.wherewego.routing;

import com.wherewego.graph.GraphCache;
import com.wherewego.graph.TransitGraph;
import com.wherewego.graph.TransitMode;
import org.springframework.stereotype.Service;

/** 좌표·역이름을 노드로 바꾸고 탐색을 돌린다. */
@Service
public class RouteService {

    private final GraphCache cache;
    private final SnapRepository snaps;

    public RouteService(GraphCache cache, SnapRepository snaps) {
        this.cache = cache;
        this.snaps = snaps;
    }

    /**
     * 출발점 하나가 그래프의 어디에 붙었는지.
     *
     * @param node 메모리 그래프의 노드 첨자
     * @param snapDistanceM 좌표로 준 경우 붙기까지 걸어야 하는 거리. 역 이름으로 준 경우 0
     */
    public record Endpoint(int node, double snapDistanceM, String label) {}

    public TransitGraph graph() {
        return cache.get();
    }

    /** 좌표를 보행망에 붙인다. */
    public Endpoint atCoordinate(TransitGraph graph, double lng, double lat) {
        var snapped = snaps.snap(graph.buildId(), lng, lat);
        if (snapped == null) {
            throw new NoSuchNodeException(
                    "%.5f,%.5f 에서 %dm 안에 보행망이 없다"
                            .formatted(lng, lat, SnapRepository.MAX_SNAP_M));
        }
        int node = graph.indexOf(snapped.nodeId());
        if (node < 0) {
            // 스냅은 DB, 탐색은 메모리다. 그 사이에 그래프가 새로 빌드되면 어긋난다.
            throw new StaleGraphException("스냅한 노드가 올려둔 그래프에 없다. 그래프가 바뀌었다");
        }
        return new Endpoint(node, snapped.distanceM(), "%.5f,%.5f".formatted(lng, lat));
    }

    /** 역·정류장 이름으로 찾는다. */
    public Endpoint atStop(TransitGraph graph, TransitMode mode, String name) {
        int node = graph.findStop(mode, name);
        if (node < 0) {
            throw new NoSuchNodeException("%s 정류장을 찾지 못했다: %s".formatted(mode, name));
        }
        return new Endpoint(node, 0, name);
    }

    /**
     * 한 쌍의 경로. 닿지 못하면 null.
     *
     * @param hour 출발 시각대 0~23, 또는 {@link Dijkstra#NO_HOUR}
     */
    public Route route(TransitGraph graph, int from, int to, int hour) {
        var result = Dijkstra.run(graph, from, new int[] {to}, hour);
        return RouteBuilder.build(graph, result, to);
    }

    /** 출발지도 도착지도 아닌 이름·좌표. */
    public static class NoSuchNodeException extends RuntimeException {
        public NoSuchNodeException(String message) {
            super(message);
        }
    }

    /** 올려둔 그래프가 DB 와 어긋났다. 다시 읽어야 한다. */
    public static class StaleGraphException extends RuntimeException {
        public StaleGraphException(String message) {
            super(message);
        }
    }
}
