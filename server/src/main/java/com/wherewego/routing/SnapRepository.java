package com.wherewego.routing;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 좌표를 그래프의 보행 노드에 붙인다.
 *
 * <p>탐색은 전부 메모리에서 돌지만 <b>이것만은 DB 에 남겨 둔다</b>. 21만 개 노드 중 가장 가까운
 * 하나를 찾는 일이고, PostGIS 의 GiST 인덱스가 이미 그걸 위해 있다. 같은 것을 메모리에서
 * 하려면 공간 인덱스를 직접 들고 있어야 하는데, 요청당 한 번 나가는 질의를 없애자고
 * 자료구조를 하나 더 유지할 이유가 없다.
 */
@Repository
public class SnapRepository {

    /** 이보다 멀면 붙이지 않는다. 서울 밖이거나 보행망이 닿지 않는 곳이다. */
    public static final int MAX_SNAP_M = 2_000;

    private final NamedParameterJdbcTemplate jdbc;

    public SnapRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param nodeId {@code graph_node.id}. 메모리 그래프의 첨자가 아니다
     * @param distanceM 실제로 떨어진 거리. 멀면 결과를 믿을 수 없다는 신호다
     */
    public record Snapped(long nodeId, double distanceM) {}

    /**
     * 가장 가까운 보행 노드. 없으면 null.
     *
     * <p>{@code <->} 연산자가 GiST 인덱스를 타고 거리순으로 바로 꺼낸다. {@code ST_DWithin} 은
     * 훑을 범위를 잘라 주는 역할이다 — 둘을 같이 써야 "2km 안에서 가장 가까운 하나"가
     * 인덱스만으로 끝난다.
     */
    public Snapped snap(long buildId, double lng, double lat) {
        var params = new MapSqlParameterSource()
                .addValue("buildId", buildId)
                .addValue("lng", lng)
                .addValue("lat", lat)
                .addValue("maxM", MAX_SNAP_M);

        var rows = jdbc.query(
                """
                SELECT n.id, ST_Distance(n.geom, q.g) AS distance_m
                FROM graph_node n
                CROSS JOIN LATERAL (
                    SELECT ST_Transform(ST_SetSRID(ST_MakePoint(:lng, :lat), 4326), 5186) AS g
                ) q
                WHERE n.build_id = :buildId AND n.kind = 'WALK'
                  AND ST_DWithin(n.geom, q.g, :maxM)
                ORDER BY n.geom <-> q.g
                LIMIT 1
                """,
                params,
                (rs, i) -> new Snapped(rs.getLong("id"), rs.getDouble("distance_m")));

        return rows.isEmpty() ? null : rows.getFirst();
    }
}
