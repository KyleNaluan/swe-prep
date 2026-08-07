package com.sweprep.backend.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.sweprep.backend.attempt.CurrentUser;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves the day-completion spine of the session loop against a real, disposable
 * Postgres (issue #19): finishing the warm-up completes the day durably, doing so twice
 * is a harmless no-op, the streak counts consecutive days and a gap breaks it, and an
 * ongoing streak still shows before today's warm-up is done. Nothing here consults
 * attempts - a completed day is its own record, which is what keeps the app-open read
 * cheap and decoupled from how the warm-up is counted.
 */
@SpringBootTest
@Testcontainers
@Transactional
class SessionPersistenceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private SessionService service;

    @Autowired
    private DayCompletionRepository days;

    @Autowired
    private CurrentUser currentUser;

    @Test
    void completingTheWarmupCompletesTheDayAndStartsTheStreak() {
        SessionStatus before = service.status();
        assertThat(before.dayComplete()).isFalse();
        assertThat(before.completedAt()).isNull();

        SessionStatus after = service.completeWarmup();
        assertThat(after.dayComplete()).isTrue();
        assertThat(after.completedAt()).isNotNull();
        assertThat(after.streak()).isEqualTo(1);

        // Durable: a fresh status read (not the returned object) still sees the day done.
        assertThat(service.status().dayComplete()).isTrue();
    }

    @Test
    void completingTheWarmupTwiceIsIdempotentAndKeepsTheFirstCompletionTime() {
        SessionStatus first = service.completeWarmup();
        Instant firstTime = first.completedAt();

        SessionStatus second = service.completeWarmup();
        assertThat(second.dayComplete()).isTrue();
        // No second row and no moved timestamp: the day was already complete.
        assertThat(second.streak()).isEqualTo(1);
        assertThat(days.find(currentUser.id(), LocalDate.now()))
                .isPresent();
        assertThat(second.completedAt()).isEqualTo(firstTime);
    }

    @Test
    void theStreakCountsConsecutiveCompletedDaysEndingToday() {
        LocalDate today = LocalDate.now();
        // Two prior days already complete, then finish today's warm-up: three in a row.
        days.markComplete(currentUser.id(), today.minusDays(2), Instant.now());
        days.markComplete(currentUser.id(), today.minusDays(1), Instant.now());

        assertThat(service.completeWarmup().streak()).isEqualTo(3);
    }

    @Test
    void anOngoingStreakShowsBeforeTodaysWarmupIsDone() {
        LocalDate today = LocalDate.now();
        days.markComplete(currentUser.id(), today.minusDays(1), Instant.now());

        // Today's warm-up is not done yet, but yesterday's streak is still live at 1...
        SessionStatus before = service.status();
        assertThat(before.dayComplete()).isFalse();
        assertThat(before.streak()).isEqualTo(1);

        // ...and finishing today extends it rather than restarting from one.
        assertThat(service.completeWarmup().streak()).isEqualTo(2);
    }

    @Test
    void aMissedDayBreaksTheStreak() {
        LocalDate today = LocalDate.now();
        // Complete two days ago but not yesterday: the gap breaks the run.
        days.markComplete(currentUser.id(), today.minusDays(2), Instant.now());

        SessionStatus before = service.status();
        assertThat(before.streak()).isZero();

        // Completing today starts a fresh streak of one.
        assertThat(service.completeWarmup().streak()).isEqualTo(1);
    }
}
