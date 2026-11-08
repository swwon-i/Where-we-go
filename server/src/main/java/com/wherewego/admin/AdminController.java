package com.wherewego.admin;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 데이터 파이프라인의 운영 기록 (계획서 §4 {@code /admin/*}).
 *
 * <pre>
 *   GET /api/v1/admin/ingest-runs          적재 회차
 *   GET /api/v1/admin/validation-summary   검수 규칙별 건수
 *   GET /api/v1/admin/validation-results   검수에 걸린 레코드 (쪽 나눔)
 *   GET /api/v1/admin/graph-stats          그래프 빌드와 수단별 규모
 *   GET /api/v1/admin/matrix-runs          행렬 계산 기록 (최근 것부터)
 *   GET /api/v1/admin/matrix-runs/daily    행렬 계산의 일별 집계
 * </pre>
 *
 * <p><b>읽기 전용이고 관리자만 본다</b>({@code WWG_ADMIN_LOGIN_IDS}, SecurityConfig).
 * 쓰기는 하나도 두지 않는다.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private static final Set<String> SEVERITIES = Set.of("ERROR", "WARN");
    private static final int MAX_RUNS = 500;
    private static final int MAX_MATRIX_DAYS = 31;

    private final AdminRepository repository;

    public AdminController(AdminRepository repository) {
        this.repository = repository;
    }

    /** @param totals 지금 {@code poi} 의 영업 · 폐업 수. 회차가 쌓인 결과다 */
    public record IngestRuns(List<AdminRepository.IngestRun> runs, Map<String, Long> totals) {}

    @GetMapping("/ingest-runs")
    public IngestRuns ingestRuns(
            @RequestParam(required = false) String source,
            @RequestParam(defaultValue = "100") int limit) {
        if (limit < 1 || limit > MAX_RUNS) {
            throw new IllegalArgumentException("limit 은 1~" + MAX_RUNS + " 입니다: " + limit);
        }
        return new IngestRuns(repository.ingestRuns(blankToNull(source), limit), repository.poiTotals());
    }

    @GetMapping("/validation-summary")
    public List<AdminRepository.RuleCount> validationSummary(
            @RequestParam(required = false) Long runId) {
        return repository.validationSummary(runId);
    }

    @GetMapping("/validation-results")
    public AdminRepository.Page<AdminRepository.ValidationResult> validationResults(
            @RequestParam(required = false) Long runId,
            @RequestParam(required = false) String rule,
            @RequestParam(required = false) String severity,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + AdminRepository.DEFAULT_PAGE_SIZE) int size) {

        if (page < 0) throw new IllegalArgumentException("page 는 0 이상입니다: " + page);
        if (size < 1 || size > AdminRepository.MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "size 는 1~" + AdminRepository.MAX_PAGE_SIZE + " 입니다: " + size);
        }
        String sev = blankToNull(severity);
        if (sev != null && !SEVERITIES.contains(sev)) {
            throw new IllegalArgumentException("severity 는 ERROR 또는 WARN 입니다: " + severity);
        }
        return repository.validationResults(runId, blankToNull(rule), sev, page, size);
    }

    /**
     * @param active 지금 경로 탐색이 쓰는 빌드. 없으면 null
     * @param counts 활성 빌드의 노드 · 엣지를 종류 × 수단으로 센 것
     * @param builds 최근 빌드 기록. 이전 빌드는 빌드할 때 정리되므로 보통 한두 줄이다
     */
    public record GraphStats(
            AdminRepository.GraphBuild active,
            List<AdminRepository.ModeCount> counts,
            List<AdminRepository.GraphBuild> builds) {}

    @GetMapping("/graph-stats")
    public GraphStats graphStats() {
        var builds = repository.graphBuilds(20);
        var active = builds.stream().filter(AdminRepository.GraphBuild::active).findFirst().orElse(null);
        var counts = active == null ? List.<AdminRepository.ModeCount>of() : active.counts();
        return new GraphStats(active, counts, builds);
    }

    @GetMapping("/matrix-runs")
    public List<AdminRepository.MatrixRun> matrixRuns(@RequestParam(defaultValue = "20") int limit) {
        if (limit < 1 || limit > MAX_RUNS) {
            throw new IllegalArgumentException("limit 은 1~" + MAX_RUNS + " 입니다: " + limit);
        }
        return repository.matrixRuns(limit);
    }

    /** @param days 오늘을 포함해 며칠 전까지. 기록은 30일만 보관한다 */
    @GetMapping("/matrix-runs/daily")
    public List<AdminRepository.MatrixDaily> matrixRunsDaily(@RequestParam(defaultValue = "30") int days) {
        if (days < 1 || days > MAX_MATRIX_DAYS) {
            throw new IllegalArgumentException("days 는 1~" + MAX_MATRIX_DAYS + " 입니다: " + days);
        }
        return repository.matrixDaily(days);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
