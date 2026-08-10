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
 * Proves the two queries the readiness picture (issue #45) reads against a real Postgres:
 * {@link AttemptRepository#solvedColdExerciseIds} - a {@code CHALLENGE} solved with no help
 * taken - and {@link AttemptRepository#explainedExerciseIds} - a self-check completed. Both
 * are machine-verdict-adjacent facts already captured on the {@code attempt} row, so this is
 * a query-shape test, not a new write path; catalogs are mocked since real content lives only
 * in the private repo (issue #14).
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
    void solvedColdCountsOnlyAChallengeSolvedWithNoHintAndNoReveal() {
        UUID user = currentUser.id();
        insert(user, "solved-clean", "CHALLENGE", AttemptOutcome.SOLVED, 0, false);
        insert(user, "solved-with-hint", "CHALLENGE", AttemptOutcome.SOLVED, 1, false);
        insert(user, "solved-with-reveal", "CHALLENGE", AttemptOutcome.SOLVED, 0, true);
        insert(user, "abandoned", "CHALLENGE", AttemptOutcome.ABANDONED, 0, false);
        insert(user, "solved-rep", "REP", AttemptOutcome.SOLVED, 0, false);

        assertThat(attempts.solvedColdExerciseIds(user)).containsExactly("solved-clean");
    }

    @Test
    void explainedExerciseIdsCountsOnlySelfCheckCompletions() {
        UUID user = currentUser.id();
        insert(user, "explained-one", "CHALLENGE", AttemptOutcome.EXPLAINED, 0, false);
        insert(user, "solved-one", "REP", AttemptOutcome.SOLVED, 0, false);

        assertThat(attempts.explainedExerciseIds(user)).containsExactly("explained-one");
    }

    private void insert(
            UUID userId, String exerciseId, String form, AttemptOutcome outcome, int hintsTaken,
            boolean failingCaseRevealed) {
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
                null));
    }
}
