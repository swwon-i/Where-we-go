package com.wherewego.matrix;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 행렬 계산 한 번을 {@code matrix_run} 에 한 줄로 남긴다.
 *
 * <p><b>기록이 계산을 망치면 안 된다.</b> 저장에 실패해도 예외를 밖으로 내지 않는다 — 운영 기록
 * 한 줄 때문에 사용자가 받을 결과를 버릴 이유가 없다.
 *
 * <h2>얼마나 쌓이나</h2>
 *
 * 하루 상한과 보관 기간이 함께 최대 용량을 정한다. 횟수 제한은 IP 마다라 IP 를 여럿 쓰면
 * 우회되지만 하루 상한은 공격 규모와 무관하다. 카운터는 메모리에 있어 재시작하면 비는데,
 * 그때 새는 양은 많아야 하루치 한 번이다.
 */
@Component
public class MatrixRunRecorder {

    private static final Logger log = LoggerFactory.getLogger(MatrixRunRecorder.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int RETENTION_DAYS = 30;

    /** 계산 한 번에서 모은 값. 실패하면 도달하지 못한 단계의 값은 null 로 남는다. */
    static final class Run {
        final UUID roomId;
        /** 정상 종료를 확인하기 전까지는 오류다. 어떤 경로로 빠져나가도 빈 값이 남지 않는다. */
        String status = "ERROR";
        Long graphBuildId;
        String graphSource;
        Integer originCount;
        Integer destCount;
        Integer unreachable;
        Integer resnapped;
        Integer settledNodes;
        Long snapMs;
        Long searchMs;
        long elapsedMs;

        Run(UUID roomId) {
            this.roomId = roomId;
        }
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final int dailyCap;
    private final Clock clock;

    private LocalDate countedDay;
    private int countToday;

    @Autowired
    public MatrixRunRecorder(
            NamedParameterJdbcTemplate jdbc,
            @Value("${wwg.matrix-run.daily-cap:50000}") int dailyCap) {
        this(jdbc, dailyCap, Clock.systemUTC());
    }

    MatrixRunRecorder(NamedParameterJdbcTemplate jdbc, int dailyCap, Clock clock) {
        this.jdbc = jdbc;
        this.dailyCap = dailyCap;
        this.clock = clock;
    }

    void save(Run run) {
        if (!admit()) return;
        try {
            jdbc.update(
                    """
                    INSERT INTO matrix_run (
                        room_id, graph_build_id, status, origin_count, dest_count, unreachable,
                        resnapped, settled_nodes, graph_source, snap_ms, search_ms, elapsed_ms)
                    VALUES (
                        :room, :build, :status, :origins, :dests, :unreachable,
                        :resnapped, :settled, :source, :snap, :search, :elapsed)
                    """,
                    new MapSqlParameterSource()
                            .addValue("room", run.roomId)
                            .addValue("build", run.graphBuildId)
                            .addValue("status", run.status)
                            .addValue("origins", run.originCount)
                            .addValue("dests", run.destCount)
                            .addValue("unreachable", run.unreachable)
                            .addValue("resnapped", run.resnapped)
                            .addValue("settled", run.settledNodes)
                            .addValue("source", run.graphSource)
                            .addValue("snap", run.snapMs)
                            .addValue("search", run.searchMs)
                            .addValue("elapsed", run.elapsedMs));
        } catch (RuntimeException e) {
            log.warn("행렬 계산 기록을 저장하지 못했다", e);
        }
    }

    /** 오늘 상한 안인가. 상한에 처음 닿은 순간에만 경고한다 — 로그가 대신 차오르면 안 된다. */
    private synchronized boolean admit() {
        var today = LocalDate.now(clock.withZone(KST));
        if (!today.equals(countedDay)) {
            countedDay = today;
            countToday = 0;
        }
        if (countToday >= dailyCap) return false;
        if (++countToday == dailyCap) {
            log.warn("행렬 계산 기록이 오늘 상한 {}건에 닿았다. 오늘은 더 저장하지 않는다", dailyCap);
        }
        return true;
    }

    /** 보관 기간이 지난 기록을 지운다. 서버 안에서 돌리므로 일일 실행 방식이 달라도 한 곳이다. */
    @Scheduled(cron = "0 30 4 * * *", zone = "Asia/Seoul")
    public void deleteExpired() {
        int deleted = jdbc.update(
                "DELETE FROM matrix_run WHERE created_at < now() - (:days * interval '1 day')",
                new MapSqlParameterSource("days", RETENTION_DAYS));
        log.info("보관 기간이 지난 행렬 계산 기록 {}건 삭제", deleted);
    }
}
