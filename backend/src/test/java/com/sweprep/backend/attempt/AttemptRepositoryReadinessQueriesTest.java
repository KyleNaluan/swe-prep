package com.sweprep.backend.attempt;

import static org.assertj.core.api.Assertions.assertThat;

import com.sweprep.backend.exercise.ContentCatalog;
import com.sweprep.backend.exercise.ExerciseCatalog;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves the queries the readiness picture reads against a real Postgres:
 * {@link AttemptRepository#solvedColdExerciseIds} (issue #45) - a {@code CHALLENGE} solved
 * with no help taken; {@link AttemptRepository#explainedExerciseIds} (issue #45) - a
 * self-check completed; and {@link AttemptRepository#lastAttemptDates} (issue #22) - the
 * staleness axis's "when was this last touched" signal. All are facts already captured on
 * the {@code attempt} row, so this is a query-shape test, not a new write path; catalogs
 * are mocked since real content lives only in the private repo (issue #14).
 */
@SpringBootTest
@Testcontainers
@Transactional
class AttemptRepositoryReadinessQueriesTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private AttemptRepository attempts;

    @Autowired
    private CurrentUser currentUser;

    @MockitoBean
    private ExerciseCatalog catalog;

    @MockitoBean
    private ContentCatalog contentCatalog;

    @Test
    void solvedColdCountsOnlyAChallengeSolvedWithNoHintNoRevealAndNoSolutionSeen() {
        UUID user = currentUser.id();
        insert(user, "solved-clean", "CHALLENGE", AttemptOutcome.SOLVED, 0, false, false);
        insert(user, "solved-with-hint", "CHALLENGE", AttemptOutcome.SOLVED, 1, false, false);
        insert(user, "solved-with-reveal", "CHALLENGE", AttemptOutcome.SOLVED, 0, true, false);
        // Issue #82: a solve tainted by a pre-pass solution reveal is excluded too.
        insert(user, "solved-with-solution-seen", "CHALLENGE", AttemptOutcome.SOLVED, 0, false, true);
        insert(user, "abandoned", "CHALLENGE", AttemptOutcome.ABANDONED, 0, false, false);
        insert(user, "solved-rep", "REP", AttemptOutcome.SOLVED, 0, false, false);

        assertThat(attempts.solvedColdExerciseIds(user)).containsExactly("solved-clean");
    }

    @Test
    void solvedColdCountsAnExerciseAgainOnceALaterCleanPassFollowsASolutionSeenOne() {
        UUID user = currentUser.id();
        // The first sitting saw the solution before passing - tainted on its own.
        insert(user, "two-attempts", "CHALLENGE", AttemptOutcome.SOLVED, 0, false, true);
        // A fresh sitting later passes clean, with the solution never seen on it - issue
        // #82's "excluded... until a later clean pass": the exists-any query is satisfied
        // by this second, untainted row alone.
        insert(user, "two-attempts", "CHALLENGE", AttemptOutcome.SOLVED, 0, false, false);

        assertThat(attempts.solvedColdExerciseIds(user)).containsExactly("two-attempts");
    }

    @Test
    void explainedExerciseIdsCountsOnlySelfCheckCompletions() {
        UUID user = currentUser.id();
        insert(user, "explained-one", "CHALLENGE", AttemptOutcome.EXPLAINED, 0, false, false);
        insert(user, "solved-one", "REP", AttemptOutcome.SOLVED, 0, false, false);

        assertThat(attempts.explainedExerciseIds(user)).containsExactly("explained-one");
    }

    @Test
    void lastAttemptDatesReturnsTheMostRecentSittingPerExerciseWhateverItsOutcome() {
        // Truncated to microseconds: Postgres' timestamp column has no finer precision, so
        // a raw Instant.now() (nanosecond) round-trips slightly rounded and fails an exact
        // equality check on the value that was never actually storable.
        UUID user = currentUser.id();
        Instant earlier = Instant.now().minusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        Instant later = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        attempts.insert(startedAt(user, "rep-1", earlier));
        attempts.insert(startedAt(user, "rep-1", later));

        assertThat(attempts.lastAttemptDates(user)).containsEntry("rep-1", later);
    }

    private Attempt startedAt(UUID userId, String exerciseId, Instant startedAt) {
        return new Attempt(
                UUID.randomUUID(),
                userId,
                exerciseId,
                exerciseId,
                "demo",
                "REP",
                AttemptOutcome.SOLVED,
                startedAt,
                startedAt,
                0,
                false,
                null,
                false,
                null,
                null,
                null,
                false);
    }

    private void insert(
            UUID userId, String exerciseId, String form, AttemptOutcome outcome, int hintsTaken,
            boolean failingCaseRevealed, boolean solutionSeen) {
        attempts.insert(new Attempt(
                UUID.randomUUID(),
                userId,
                exerciseId,
                exerciseId,
                "demo",
                form,
                outcome,
                Instant.now(),
                Instant.now(),
                hintsTaken,
                failingCaseRevealed,
                null,
                false,
                null,
                null,
                null,
                solutionSeen));
    }
}
