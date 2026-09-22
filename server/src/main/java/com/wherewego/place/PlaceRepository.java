package com.wherewego.place;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * POI 조회.
 *
 * <p>공간 연산을 SQL 로 직접 쓴다. 저장 좌표계가 5186(미터)이므로 반경을 그대로 넘기고,
 * 4326 변환은 결과를 내보낼 때만 한다 — 검색 조건에 ST_Transform 을 걸면 GiST 인덱스를 못 탄다.
 */
@Repository
public class PlaceRepository {

    /** 기본 반경(m). */
    public static final int DEFAULT_RADIUS_M = 2_000;
    /** 최대 반경(m). 넘으면 잘라낸다. */
    public static final int MAX_RADIUS_M = 10_000;
    /** 한 번에 돌려줄 최대 건수. */
    public static final int MAX_LIMIT = 200;

    private static final String SELECT_COLUMNS =
            """
            SELECT p.id, p.name, p.category_code, p.category_raw,
                   p.road_address, p.jibun_address, p.phone, p.status,
                   ST_X(ST_Transform(p.geom, 4326)) AS lng,
                   ST_Y(ST_Transform(p.geom, 4326)) AS lat
            """;

    /** 상세에 싣는 검수 기록 최대 건수. 한 레코드가 여러 회차에 걸쳐 같은 규칙에 계속 걸릴 수 있다. */
    public static final int MAX_VALIDATIONS = 50;

    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    public PlaceRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 좌표 기준 반경 검색. {@code ST_DWithin} + GiST 인덱스를 타는 경로다.
     *
     * @param lng WGS84 경도
     * @param lat WGS84 위도
     */
    public List<Place> findNearby(
            double lng, double lat, int radiusM, String category, boolean includeClosed, int limit) {

        var params = new MapSqlParameterSource()
                .addValue("lng", lng)
                .addValue("lat", lat)
                .addValue("radius", clampRadius(radiusM))
                .addValue("category", category)
                .addValue("limit", clampLimit(limit));

        // 입력 좌표를 5186 으로 한 번 옮겨두고, 이후 비교는 전부 미터 단위로 한다.
        var sql = SELECT_COLUMNS
                + """
                , ST_Distance(p.geom, q.g) AS distance_m
                FROM poi p
                CROSS JOIN LATERAL (
                    SELECT ST_Transform(ST_SetSRID(ST_MakePoint(:lng, :lat), 4326), 5186) AS g
                ) q
                WHERE ST_DWithin(p.geom, q.g, :radius)
                """
                + statusClause(includeClosed)
                + categoryClause(category)
                + """
                ORDER BY distance_m
                LIMIT :limit
                """;

        return jdbc.query(sql, params, mapper(true));
    }

    /** 상호명 부분 일치 검색. pg_trgm GIN 인덱스를 쓴다. */
    public List<Place> search(
            String q,
            Double lng,
            Double lat,
            int radiusM,
            String category,
            boolean includeClosed,
            int limit) {

        var params = new MapSqlParameterSource()
                .addValue("q", "%" + q + "%")
                .addValue("category", category)
                .addValue("limit", clampLimit(limit));

        var hasOrigin = lng != null && lat != null;
        var sql = new StringBuilder(SELECT_COLUMNS);

        if (hasOrigin) {
            params.addValue("lng", lng).addValue("lat", lat).addValue("radius", clampRadius(radiusM));
            sql.append("""
                    , ST_Distance(p.geom, q.g) AS distance_m
                    FROM poi p
                    CROSS JOIN LATERAL (
                        SELECT ST_Transform(ST_SetSRID(ST_MakePoint(:lng, :lat), 4326), 5186) AS g
                    ) q
                    WHERE ST_DWithin(p.geom, q.g, :radius) AND p.name LIKE :q
                    """);
        } else {
            sql.append("""
                    , NULL::float8 AS distance_m
                    FROM poi p
                    WHERE p.name LIKE :q
                    """);
        }

        sql.append(statusClause(includeClosed)).append(categoryClause(category));
        sql.append(hasOrigin ? "ORDER BY distance_m\n" : "ORDER BY p.name\n").append("LIMIT :limit");

        return jdbc.query(sql.toString(), params, mapper(hasOrigin));
    }

    private static String statusClause(boolean includeClosed) {
        return includeClosed ? "" : "  AND p.status = 'ACTIVE'\n";
    }

    private static String categoryClause(String category) {
        return category == null || category.isBlank() ? "" : "  AND p.category_code = :category\n";
    }

    /**
     * 단건 상세. 폐업한 곳도 돌려준다 — 북마크가 가리키는 곳이 나중에 폐업할 수 있고,
     * 그때 "왜 검색에 안 나오지" 를 여기서 확인해야 한다.
     *
     * <p>검수 기록은 인허가번호({@code target_id})로 잇되 <b>회차의 원천까지 맞춘다</b>.
     * 일반음식점과 휴게음식점은 인허가번호 체계를 따로 쓰므로 번호만으로는 다른 원천의 기록이
     * 섞일 수 있다.
     */
    public Optional<PlaceDetail> findDetail(long poiId) {
        var params = new MapSqlParameterSource("id", poiId).addValue("limit", MAX_VALIDATIONS);
        var rows = jdbc.query(SELECT_COLUMNS + """
                , p.source, p.source_id, p.licensed_date, p.closed_date,
                  f.id AS f_id, f.snapshot_date AS f_date, f.started_at AS f_at,
                  l.id AS l_id, l.snapshot_date AS l_date, l.started_at AS l_at
                FROM poi p
                LEFT JOIN ingest_run f ON f.id = p.first_seen_run_id
                LEFT JOIN ingest_run l ON l.id = p.last_seen_run_id
                WHERE p.id = :id
                """, params, (rs, i) -> new PlaceDetail(
                        mapper(false).mapRow(rs, i),
                        rs.getString("source"),
                        rs.getString("source_id"),
                        rs.getObject("licensed_date", LocalDate.class),
                        rs.getObject("closed_date", LocalDate.class),
                        run(rs, "f"),
                        run(rs, "l"),
                        List.of()));
        if (rows.isEmpty()) return Optional.empty();

        var validations = jdbc.query("""
                SELECT v.run_id, v.rule_code, v.severity, v.detail::text AS detail, v.created_at
                FROM poi p
                JOIN validation_result v ON v.target_id = p.source_id
                JOIN ingest_run r ON r.id = v.run_id AND r.source = p.source
                WHERE p.id = :id
                ORDER BY v.run_id DESC, v.severity, v.rule_code
                LIMIT :limit
                """, params, (rs, i) -> new PlaceDetail.Validation(
                        rs.getLong("run_id"),
                        rs.getString("rule_code"),
                        rs.getString("severity"),
                        rs.getString("detail") == null ? null : json.readTree(rs.getString("detail")),
                        rs.getObject("created_at", OffsetDateTime.class)));

        var d = rows.getFirst();
        return Optional.of(new PlaceDetail(
                d.place(), d.source(), d.sourceId(), d.licensedDate(), d.closedDate(),
                d.firstSeen(), d.lastSeen(), validations));
    }

    private static PlaceDetail.Run run(java.sql.ResultSet rs, String prefix) throws java.sql.SQLException {
        Long id = rs.getObject(prefix + "_id", Long.class);
        if (id == null) return null;
        return new PlaceDetail.Run(
                id,
                rs.getObject(prefix + "_date", LocalDate.class),
                rs.getObject(prefix + "_at", OffsetDateTime.class));
    }

    static int clampRadius(int radiusM) {
        if (radiusM <= 0) return DEFAULT_RADIUS_M;
        return Math.min(radiusM, MAX_RADIUS_M);
    }

    static int clampLimit(int limit) {
        if (limit <= 0) return 50;
        return Math.min(limit, MAX_LIMIT);
    }

    private static RowMapper<Place> mapper(boolean withDistance) {
        return (rs, rowNum) -> new Place(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("category_code"),
                rs.getString("category_raw"),
                rs.getString("road_address"),
                rs.getString("jibun_address"),
                rs.getString("phone"),
                rs.getDouble("lng"),
                rs.getDouble("lat"),
                rs.getString("status"),
                withDistance ? round1(rs.getDouble("distance_m")) : null);
    }

    private static Double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
