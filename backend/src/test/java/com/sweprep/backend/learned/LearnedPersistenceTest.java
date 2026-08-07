package com.sweprep.backend.learned;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.sweprep.backend.attempt.Attempt;
import com.sweprep.backend.attempt.AttemptService;
import com.sweprep.backend.attempt.CurrentUser;
import com.sweprep.backend.attempt.Submission;
import com.sweprep.backend.attempt.SubmissionRepository;
import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.ExerciseCatalog;
import com.sweprep.backend.grader.Verdict;
import com.sweprep.backend.learned.LearnedState.Status;
import com.sweprep.backend.testsupport.Fixtures;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
 * Proves the successive-relearning criterion (issue #38) end to end against a real,
 * disposable Postgres: clean machine-verdict passes flow from the real grade path into the
 * derived learned state, spacing across calendar days is what graduates an item, and - the
 * boundary the ruling is emphatic about - a self-check "history full of passes" leaves the
 * objective competence signal completely untouched, because the grade path never writes a
 * {@code PASSED} row for it in the first place.
 *
 * <p>Spacing is exercised by inserting {@code PASSED} submissions with controlled timestamps
 * (the real clock only ever produces "now"), so the query, the calendar-day bucketing in the
 * app's clock zone, and the criterion are all proven against Postgres rather than mocked.
 */
@SpringBootTest
@Testcontainers
@Transactional
class LearnedPersistenceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private AttemptService attemptService;

    @Autowired
    private LearnedService learned;

    @Autowired
    private SubmissionRepository submissions;

    @Autowired
    private CurrentUser currentUser;

    @Autowired
    private Clock clock;

    @MockitoBean
    private ExerciseCatalog catalog;

    private ZoneId zone;

    @BeforeEach
    void setUp() {
        zone = clock.getZone();
        Exercise concept = Fixtures.concept(); // Choice + AnswerKey: machine-graded Check.
        Exercise selfCheck = Fixtures.explain(); // FreeText + SelfCheck: never machine-graded.
        when(catalog.byId("concept-demo")).thenReturn(Optional.of(concept));
        when(catalog.byId("explain-demo")).thenReturn(Optional.of(selfCheck));
    }

    @Test
    void aRealCleanPassBecomesOneSpacedSessionThroughTheGradePath() {
        Attempt attempt = attemptService.start("concept-demo");
        attemptService.submit(attempt.id(), "B"); // the correct option: a real PASSED verdict.

        LearnedState state = learned.stateFor("concept-demo");
        // One clean pass today: on the way to learned (N = 3), not there yet.
        assertThat(state.status()).isEqualTo(Status.LEARNING);
        assertThat(state.spacedSessions()).isEqualTo(1);
    }

    @Test
    void cleanPassesAcrossSpacedCalendarDaysGraduate() {
        UUID attemptId = openAttempt("concept-demo");
        // Days 0, 1, 4 - gaps of 1 and 3 clear the default [1, 3, ...] ladder.
        insertPass(attemptId, dayInstant(0));
        insertPass(attemptId, dayInstant(1));
        insertPass(attemptId, dayInstant(4));

        LearnedState state = learned.stateFor("concept-demo");
        assertThat(state.status()).isEqualTo(Status.LEARNED);
        assertThat(state.spacedSessions()).isEqualTo(3);
    }

    @Test
    void repeatedCleanPassesInOneCalendarDayDoNotGraduate() {
        UUID attemptId = openAttempt("concept-demo");
        // Three passes, same calendar day, hours apart: one session, not three.
        insertPass(attemptId, dayInstant(0).plusSeconds(3_600));
        insertPass(attemptId, dayInstant(0).plusSeconds(7_200));
        insertPass(attemptId, dayInstant(0).plusSeconds(10_800));

        LearnedState state = learned.stateFor("concept-demo");
        assertThat(state.status()).isEqualTo(Status.LEARNING);
        assertThat(state.spacedSessions()).isEqualTo(1);
    }

    @Test
    void aHistoryFullOfSelfCheckPassesLeavesTheObjectiveSignalUntouched() {
        // A self-check is never machine-graded: every attempt to "submit" it for a verdict
        // is refused by the grade path, so no PASSED row can ever be written...
        Attempt attempt = attemptService.start("explain-demo");
        for (int i = 0; i < 5; i++) {
            UUID id = attempt.id();
            assertThatThrownBy(() -> attemptService.submit(id, "my explanation"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        // ...and so the objective competence signal is structurally empty: no clean passes,
        // and the item stays NEW no matter how many times it was "practised".
        assertThat(submissions.cleanPassInstants(currentUser.id(), "explain-demo")).isEmpty();
        assertThat(learned.stateFor("explain-demo").status()).isEqualTo(Status.NEW);
        assertThat(learned.statesForAll()).doesNotContainKey("explain-demo");
    }

    @Test
    void aSelfCheckNeverGraduatesEvenWhileAMachineCheckDoes() {
        // The contrast, side by side: identical practice cadence, opposite objective effect.
        UUID conceptAttempt = openAttempt("concept-demo");
        insertPass(conceptAttempt, dayInstant(0));
        insertPass(conceptAttempt, dayInstant(1));
        insertPass(conceptAttempt, dayInstant(4));

        // The self-check cannot even produce a submission row to insert, so it never moves.
        assertThat(learned.stateFor("concept-demo").status()).isEqualTo(Status.LEARNED);
        assertThat(learned.stateFor("explain-demo").status()).isEqualTo(Status.NEW);
    }

    // --- helpers ---------------------------------------------------------------

    private UUID openAttempt(String exerciseId) {
        return attemptService.start(exerciseId).id();
    }

    private Instant dayInstant(int dayOffset) {
        return LocalDate.of(2026, 1, 1).plusDays(dayOffset).atStartOfDay(zone).toInstant();
    }

    private void insertPass(UUID attemptId, Instant when) {
        submissions.insert(new Submission(
                UUID.randomUUID(),
                attemptId,
                when,
                "B",
                Verdict.Outcome.PASSED,
                1,
                1,
                "",
                0));
    }
}
