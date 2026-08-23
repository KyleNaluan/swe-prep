package com.sweprep.backend.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.sweprep.backend.attempt.Attempt;
import com.sweprep.backend.attempt.AttemptOutcome;
import com.sweprep.backend.attempt.AttemptRepository;
import com.sweprep.backend.attempt.CurrentUser;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
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
 * ongoing streak still shows before today's warm-up is done. Also proves the repair
 * mechanic (issue #22) is wired end-to-end - a missed day bridged by a real double
 * session (a solved {@code CHALLENGE} attempt) against real Postgres data; the exact
 * repair-count and monthly-cap arithmetic is pinned with deterministic dates in {@link
 * StreakCalculatorTest} instead of here, since this test runs on the real wall clock.
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
    private AttemptRepository attempts;

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

    // These two prove the repair mechanic is wired end-to-end against a real database.
    // The exact repair-count arithmetic and the monthly cap (which depend on calendar-
    // month boundaries relative to "today") are pinned with fixed dates and asserted
    // precisely in StreakCalculatorTest instead - this test runs on the real wall clock
    // (SessionConfig's Clock bean), so it deliberately avoids any assertion that would
    // flip depending on what day of the month it happens to run on.
    @Test
    void aMissedDayIsRepairedByADoubleSessionTheDayAfter() {
        LocalDate today = LocalDate.now();
        // A live streak through three days ago, then a miss yesterday...
        days.markComplete(currentUser.id(), today.minusDays(3), Instant.now());
        days.markComplete(currentUser.id(), today.minusDays(2), Instant.now());
        // ...repaired by finishing the warm-up and solving a challenge today.
        solveChallenge(today);

        SessionStatus after = service.completeWarmup();

        // The gap does not break the run: today (1) + the two pre-miss days (2) = 3.
        assertThat(after.streak()).isEqualTo(3);
    }

    @Test
    void aMissedDayWithNoChallengeSolvedIsNotRepaired() {
        LocalDate today = LocalDate.now();
        days.markComplete(currentUser.id(), today.minusDays(3), Instant.now());
        days.markComplete(currentUser.id(), today.minusDays(2), Instant.now());
        // Warm-up only, no challenge - not a double session.

        SessionStatus after = service.completeWarmup();

        assertThat(after.streak()).isEqualTo(1);
    }

    private void solveChallenge(LocalDate on) {
        Instant at = on.atStartOfDay(ZoneId.systemDefault()).toInstant();
        attempts.insert(new Attempt(
                UUID.randomUUID(),
                currentUser.id(),
                "challenge-" + on,
                "A challenge",
                "algorithms",
                "CHALLENGE",
                AttemptOutcome.SOLVED,
                at,
                at,
                0,
                false,
                null,
                false,
                null,
                null,
                null,
                false));
    }
}
