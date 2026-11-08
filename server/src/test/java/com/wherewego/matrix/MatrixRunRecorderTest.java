package com.wherewego.matrix;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class MatrixRunRecorderTest {

    private static final class MovableClock extends Clock {
        private Instant now;

        MovableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    // 한국 시간 2026-11-08 23:50
    private final MovableClock clock = new MovableClock(Instant.parse("2026-11-08T14:50:00Z"));

    private MatrixRunRecorder.Run run() {
        return new MatrixRunRecorder.Run(UUID.randomUUID());
    }

    private void verifyInserts(int expected) {
        verify(jdbc, times(expected)).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    @DisplayName("하루 상한을 넘으면 저장하지 않는다")
    void stopsAtDailyCap() {
        var recorder = new MatrixRunRecorder(jdbc, 2, clock);
        for (int i = 0; i < 5; i++) recorder.save(run());
        verifyInserts(2);
    }

    @Test
    @DisplayName("상한은 한국 시간 자정에 다시 시작한다")
    void capResetsAtKoreanMidnight() {
        var recorder = new MatrixRunRecorder(jdbc, 1, clock);
        recorder.save(run());
        recorder.save(run());
        verifyInserts(1);

        clock.advance(Duration.ofMinutes(20));  // 11/09 00:10
        recorder.save(run());
        verifyInserts(2);
    }

    @Test
    @DisplayName("저장이 실패해도 예외를 내지 않는다 — 기록 때문에 계산 결과를 버리면 안 된다")
    void saveFailureDoesNotPropagate() {
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class)))
                .thenThrow(new DataAccessResourceFailureException("DB 끊김"));
        var recorder = new MatrixRunRecorder(jdbc, 10, clock);

        assertThatCode(() -> recorder.save(run())).doesNotThrowAnyException();
    }
}
