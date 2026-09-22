package com.wherewego.admin;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 파이프라인 기록 조회. ETL 이 쌓아 둔 테이블을 읽기만 한다 — 여기서 새로 계산하는 값은
 * 그래프의 수단별 개수 하나뿐이다({@link #modeBreakdown}).
 */
@Repository
public class AdminRepository {

    /** 검수 결과 한 쪽의 기본 · 최대 건수. 계획서 §4. */
    public static final int DEFAULT_PAGE_SIZE = 50;
    public static final int MAX_PAGE_SIZE = 500;

    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    /** 빌드 번호 → 수단별 개수. 빌드는 한 번 만들어지면 바뀌지 않으므로 한 번만 센다. */
    private final Map<Long, List<ModeCount>> breakdownCache = new ConcurrentHashMap<>();

    public AdminRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── 적재 회차 ──────────────────────────────────────────────────────────

    /**
     * 적재 회차 하나. {@code rows*} 는 SKIPPED·FAILED 회차에서 비어 있다.
     *
     * @param rowsNew 이번 회차에 처음 보인 레코드
     * @param rowsKept 직전 회차에도 있던 레코드
     * @param rowsVanished 직전 회차에 있었는데 파일에서 사라진 레코드
     * @param rowsClosed 영업 → 폐업으로 바뀐 레코드. 사라짐과는 다른 사건이다
     */
    public record IngestRun(
            long id,
            String source,
            LocalDate snapshotDate,
            String mode,
            String status,
            String sourceHash,
            Integer rowsTotal,
            Integer rowsValid,
            Integer rowsRejected,
            Integer rowsNew,
            Integer rowsKept,
            Integer rowsVanished,
            Integer rowsClosed,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            Long durationMs,
            String errorMessage) {}

    public List<IngestRun> ingestRuns(String source, int limit) {
        var sql = """
                SELECT * FROM ingest_run
                WHERE (CAST(:source AS text) IS NULL OR source = :source)
                ORDER BY id DESC
                LIMIT :limit
                """;
        return jdbc.query(sql,
                new MapSqlParameterSource("source", source).addValue("limit", limit),
                (rs, i) -> new IngestRun(
                        rs.getLong("id"),
                        rs.getString("source"),
                        rs.getObject("snapshot_date", LocalDate.class),
                        rs.getString("mode"),
                        rs.getString("status"),
                        rs.getString("source_hash"),
                        integer(rs, "rows_total"),
                        integer(rs, "rows_valid"),
                        integer(rs, "rows_rejected"),
                        integer(rs, "rows_new"),
                        integer(rs, "rows_kept"),
                        integer(rs, "rows_vanished"),
                        integer(rs, "rows_closed"),
                        rs.getObject("started_at", OffsetDateTime.class),
                        rs.getObject("finished_at", OffsetDateTime.class),
                        rs.getObject("duration_ms", Long.class),
                        rs.getString("error_message")));
    }

    /** 영업 중 · 폐업 POI 수. 적재가 쌓인 결과가 지금 얼마인지. */
    public Map<String, Long> poiTotals() {
        var out = new java.util.LinkedHashMap<String, Long>();
        jdbc.query("SELECT status, count(*) AS n FROM poi GROUP BY status ORDER BY status",
                rs -> { out.put(rs.getString("status"), rs.getLong("n")); });
        return out;
    }

    // ── 검수 ──────────────────────────────────────────────────────────────

    /** 규칙 하나의 회차별 집계. */
    public record RuleCount(long runId, String source, String ruleCode, String severity, long count) {}

    /**
     * 검수 규칙별 건수.
     *
     * <p>{@code runId} 가 없으면 <b>원천마다</b> 검수 결과가 있는 가장 최근 회차를 고른다.
     * 원천(일반음식점·휴게음식점)마다 회차가 따로 쌓이고, SKIPPED 회차에는 검수 결과가 없다 —
     * 그냥 "가장 최근 회차"를 고르면 어제와 같아 건너뛴 날마다 빈 표가 나온다.
     */
    public List<RuleCount> validationSummary(Long runId) {
        var sql = """
                WITH target AS (
                    SELECT DISTINCT ON (r.source) r.id, r.source
                    FROM ingest_run r
                    WHERE (CAST(:runId AS bigint) IS NULL OR r.id = :runId)
                      AND EXISTS (SELECT 1 FROM validation_result v WHERE v.run_id = r.id)
                    ORDER BY r.source, r.id DESC
                )
                SELECT t.id AS run_id, t.source, v.rule_code, v.severity, count(*) AS n
                FROM target t JOIN validation_result v ON v.run_id = t.id
                GROUP BY t.id, t.source, v.rule_code, v.severity
                ORDER BY t.source, v.severity, n DESC
                """;
        return jdbc.query(sql, new MapSqlParameterSource("runId", runId),
                (rs, i) -> new RuleCount(
                        rs.getLong("run_id"), rs.getString("source"), rs.getString("rule_code"),
                        rs.getString("severity"), rs.getLong("n")));
    }

    /** 검수에 걸린 레코드 하나. {@code detail} 은 규칙마다 모양이 다른 JSON 이다. */
    public record ValidationResult(
            long id, long runId, String ruleCode, String severity, String targetId, JsonNode detail) {}

    public record Page<T>(long total, int page, int size, List<T> items) {}

    public Page<ValidationResult> validationResults(
            Long runId, String ruleCode, String severity, int page, int size) {

        var params = new MapSqlParameterSource()
                .addValue("runId", runId)
                .addValue("rule", ruleCode)
                .addValue("severity", severity)
                .addValue("limit", size)
                .addValue("offset", (long) page * size);
        var where = """
                WHERE (CAST(:runId AS bigint) IS NULL OR run_id = :runId)
                  AND (CAST(:rule AS text) IS NULL OR rule_code = :rule)
                  AND (CAST(:severity AS text) IS NULL OR severity = :severity)
                """;

        Long total = jdbc.queryForObject("SELECT count(*) FROM validation_result " + where, params, Long.class);
        var items = jdbc.query(
                "SELECT id, run_id, rule_code, severity, target_id, detail::text AS detail "
                        + "FROM validation_result " + where + " ORDER BY id LIMIT :limit OFFSET :offset",
                params,
                (rs, i) -> new ValidationResult(
                        rs.getLong("id"), rs.getLong("run_id"), rs.getString("rule_code"),
                        rs.getString("severity"), rs.getString("target_id"),
                        parse(rs.getString("detail"))));
        return new Page<>(total == null ? 0 : total, page, size, items);
    }

    // ── 그래프 ────────────────────────────────────────────────────────────

    /** 그래프 빌드 기록 한 줄. */
    public record GraphBuild(
            long id,
            String status,
            boolean active,
            String area,
            Float transferFallbackRatio,
            Integer isolatedNodes,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            Long durationMs,
            String errorMessage) {}

    public List<GraphBuild> graphBuilds(int limit) {
        return jdbc.query("SELECT * FROM graph_build ORDER BY id DESC LIMIT :limit",
                new MapSqlParameterSource("limit", limit),
                (rs, i) -> new GraphBuild(
                        rs.getLong("id"),
                        rs.getString("status"),
                        rs.getBoolean("is_active"),
                        rs.getString("area"),
                        rs.getObject("transfer_fallback_ratio", Float.class),
                        integer(rs, "isolated_nodes"),
                        rs.getObject("started_at", OffsetDateTime.class),
                        rs.getObject("finished_at", OffsetDateTime.class),
                        rs.getObject("duration_ms", Long.class),
                        rs.getString("error_message")));
    }

    /**
     * 노드·엣지를 종류 × 수단으로 센 것.
     *
     * @param mode {@code SUBWAY} / {@code BUS}, 보행망은 {@code WALK}
     */
    public record ModeCount(String what, String kind, String mode, long count) {}

    /**
     * 빌드의 수단별 노드·엣지 수.
     *
     * <p>{@code graph_build} 의 개수 컬럼으로 대신하지 않는 이유 — 그 컬럼은 버스를 넣기 전에
     * 만들어져 <b>지하철만</b> 센다(정류장 402 · 플랫폼 562 로 기록되는데 버스 정류장 11,042 ·
     * 플랫폼 36,606 이 빠져 있다). 그래프 테이블에서 직접 센다. 0.4초쯤 걸려 빌드마다 한 번만 센다.
     *
     * <p>엣지의 수단은 도착 노드가 걸린 정류장의 수단이다. 보행망에서 역으로 들어가는 ACCESS 는
     * 역 쪽 수단을, 역에서 보행망으로 나오는 ACCESS 는 보행({@code WALK})으로 센다.
     */
    public List<ModeCount> modeBreakdown(long buildId) {
        return breakdownCache.computeIfAbsent(buildId, this::countModes);
    }

    private List<ModeCount> countModes(long buildId) {
        var params = new MapSqlParameterSource("build", buildId);
        var nodes = jdbc.query("""
                SELECT 'NODE' AS what, n.kind, coalesce(s.mode, 'WALK') AS mode, count(*) AS n
                FROM graph_node n LEFT JOIN transit_stop s ON s.id = n.stop_id
                WHERE n.build_id = :build
                GROUP BY n.kind, s.mode
                """, params, AdminRepository::modeCount);
        var edges = jdbc.query("""
                SELECT 'EDGE' AS what, e.kind, coalesce(s.mode, 'WALK') AS mode, count(*) AS n
                FROM graph_edge e
                JOIN graph_node t ON t.id = e.to_node_id
                LEFT JOIN transit_stop s ON s.id = t.stop_id
                WHERE e.build_id = :build
                GROUP BY e.kind, s.mode
                """, params, AdminRepository::modeCount);
        var all = new java.util.ArrayList<ModeCount>(nodes);
        all.addAll(edges);
        return List.copyOf(all);
    }

    // ── 공통 ──────────────────────────────────────────────────────────────

    private static ModeCount modeCount(ResultSet rs, int i) throws SQLException {
        return new ModeCount(rs.getString("what"), rs.getString("kind"), rs.getString("mode"), rs.getLong("n"));
    }

    private static Integer integer(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, Integer.class);
    }

    private JsonNode parse(String text) {
        return text == null ? null : json.readTree(text);
    }
}
