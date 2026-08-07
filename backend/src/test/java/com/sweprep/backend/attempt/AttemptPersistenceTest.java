package com.sweprep.backend.attempt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.ExerciseCatalog;
import com.sweprep.backend.grader.Verdict;
import com.sweprep.backend.testsupport.Fixtures;
import java.util.List;
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
 * Proves the whole ticket against a real, disposable Postgres: attempts and
 * submissions persist and are queryable per user, an abandoned attempt is recorded
 * as abandoned (not an absence), and the record captures submission count, the
 * failing-case reveal and the outcome (issue #15). Grading is exercised through the
 * real graders; the concept fixture routes to the answer-key grader, so no code is
 * compiled and the test stays fast while still going end to end through the service.
 *
 * <p>The catalog is mocked so the synthetic fixtures stand in for real content, which
 * lives only in the private repo (issue #14). Re-reading each record through a fresh
 * repository query - not the in-memory object the service returned - is what proves
 * durability: the data is in Postgres, not just in a field.
 */
@SpringBootTest
@Testcontainers
@Transactional
class AttemptPersistenceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private AttemptService service;

    @Autowired
    private AttemptRepository attempts;

    @Autowired
    private SubmissionRepository submissions;

    @MockitoBean
    private ExerciseCatalog catalog;

    private Exercise concept;

    @BeforeEach
    void setUp() {
        concept = Fixtures.concept();
        when(catalog.byId("concept-demo")).thenReturn(Optional.of(concept));
        when(catalog.byId("missing")).thenReturn(Optional.empty());
    }

    @Test
    void aPassingSubmissionSolvesTheAttemptAndBothPersist() {
        Attempt started = service.start("concept-demo");
        Submission submission = service.submit(started.id(), "B");

        assertThat(submission.outcome()).isEqualTo(Verdict.Outcome.PASSED);

        // Re-read from the database, not the returned object, to prove durability.
        Attempt reloaded = attempts.findById(started.id()).orElseThrow();
        assertThat(reloaded.outcome()).isEqualTo(AttemptOutcome.SOLVED);
        assertThat(reloaded.endedAt()).isNotNull();
        assertThat(reloaded.exerciseTitle()).isEqualTo("Concept Demo");
        assertThat(reloaded.form()).isEqualTo("REP");

        List<Submission> stored = submissions.findByAttempt(started.id());
        assertThat(stored).singleElement().satisfies(s -> {
            assertThat(s.response()).isEqualTo("B");
            assertThat(s.outcome()).isEqualTo(Verdict.Outcome.PASSED);
        });
    }

    @Test
    void aWrongSubmissionLeavesTheAttemptOpenAndCountsEachTry() {
        Attempt started = service.start("concept-demo");
        service.submit(started.id(), "A");

        Attempt afterWrong = attempts.findById(started.id()).orElseThrow();
        assertThat(afterWrong.outcome()).isEqualTo(AttemptOutcome.IN_PROGRESS);
        assertThat(submissions.countByAttempt(started.id())).isEqualTo(1);

        service.submit(started.id(), "B");
        assertThat(submissions.countByAttempt(started.id())).isEqualTo(2);
        assertThat(attempts.findById(started.id()).orElseThrow().outcome())
                .isEqualTo(AttemptOutcome.SOLVED);
    }

    @Test
    void anAbandonedAttemptIsRecordedAsAbandoned() {
        Attempt started = service.start("concept-demo");
        service.abandon(started.id());

        Attempt reloaded = attempts.findById(started.id()).orElseThrow();
        assertThat(reloaded.outcome()).isEqualTo(AttemptOutcome.ABANDONED);
        assertThat(reloaded.endedAt()).isNotNull();
    }

    @Test
    void revealingTheFailingCaseIsRecordedNeverPenalised() {
        Attempt started = service.start("concept-demo");
        service.recordFailingCaseReveal(started.id());

        Attempt reloaded = attempts.findById(started.id()).orElseThrow();
        assertThat(reloaded.failingCaseRevealed()).isTrue();
        // Recording the reveal does not end the attempt.
        assertThat(reloaded.outcome()).isEqualTo(AttemptOutcome.IN_PROGRESS);
    }

    @Test
    void historyIsQueryablePerUserNewestFirstWithCounts() {
        Attempt first = service.start("concept-demo");
        service.submit(first.id(), "A");
        service.submit(first.id(), "B");
        Attempt second = service.start("concept-demo");

        List<AttemptWithCount> history = service.history();

        // Newest first: the second sitting leads.
        assertThat(history).extracting(h -> h.attempt().id()).containsExactly(second.id(), first.id());
        assertThat(history.get(0).submissionCount()).isZero();
        assertThat(history.get(1).submissionCount()).isEqualTo(2);
    }

    @Test
    void startingOnAnUnknownExerciseIsNotFound() {
        assertThatThrownBy(() -> service.start("missing"))
                .isInstanceOf(AttemptNotFoundException.class);
    }

    @Test
    void submittingToAnEndedAttemptIsRejected() {
        Attempt started = service.start("concept-demo");
        service.abandon(started.id());

        UUID id = started.id();
        assertThatThrownBy(() -> service.submit(id, "B"))
                .isInstanceOf(IllegalAttemptStateException.class);
    }
}
