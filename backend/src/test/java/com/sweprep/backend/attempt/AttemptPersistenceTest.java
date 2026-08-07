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
    private Exercise pair;

    @BeforeEach
    void setUp() {
        concept = Fixtures.concept();
        pair = Fixtures.pairInAnyOrder();
        when(catalog.byId("concept-demo")).thenReturn(Optional.of(concept));
        when(catalog.byId("pair-in-any-order")).thenReturn(Optional.of(pair));
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
    void revealingTheFailingCaseIsRecordedWithItsHypothesisNeverPenalised() {
        Attempt started = service.start("concept-demo");
        RevealResult result = service.revealFailingCase(started.id(), "A", "I think option A is a trap");

        // A concept exercise is judged by an answer key, so there is no case to disclose;
        // the reveal and its hypothesis are still recorded.
        assertThat(result.failingCase()).isNull();

        Attempt reloaded = attempts.findById(started.id()).orElseThrow();
        assertThat(reloaded.failingCaseRevealed()).isTrue();
        assertThat(reloaded.revealHypothesis()).isEqualTo("I think option A is a trap");
        // Recording the reveal does not end the attempt or count against it.
        assertThat(reloaded.outcome()).isEqualTo(AttemptOutcome.IN_PROGRESS);
    }

    @Test
    void revealingACodeFailureDisclosesTheCaseInputExpectedAndActual() {
        Attempt started = service.start("pair-in-any-order");
        // Drops the second element, so the [1,2] and [5,3] cases fail and [7,7] passes.
        String dropsSecond =
                """
                class Solution {
                    public int[] pair(int a, int b) {
                        return new int[] {a, a};
                    }
                }
                """;
        service.submit(started.id(), dropsSecond);

        RevealResult result = service.revealFailingCase(started.id(), dropsSecond, "");
        assertThat(result.failingCase()).isNotNull();
        assertThat(result.failingCase().input().toString()).isEqualTo("[1,2]");
        assertThat(result.failingCase().expected().toString()).isEqualTo("[1,2]");
        assertThat(result.failingCase().actual().toString()).isEqualTo("[1,1]");

        Attempt reloaded = attempts.findById(started.id()).orElseThrow();
        assertThat(reloaded.failingCaseRevealed()).isTrue();
        // A blank hypothesis is allowed (skipping is not forced) and stored as null.
        assertThat(reloaded.revealHypothesis()).isNull();
        assertThat(reloaded.outcome()).isEqualTo(AttemptOutcome.IN_PROGRESS);
    }

    @Test
    void takingHintsClimbsTheLadderInOrderAndRecordsTheRungReached() {
        Attempt started = service.start("pair-in-any-order");

        HintResult first = service.takeHint(started.id());
        assertThat(first.rungsTaken()).isEqualTo(1);
        assertThat(first.totalRungs()).isEqualTo(3);
        assertThat(first.revealed().name()).isEqualTo("Pattern");
        assertThat(attempts.findById(started.id()).orElseThrow().hintsTaken()).isEqualTo(1);

        HintResult second = service.takeHint(started.id());
        assertThat(second.rungsTaken()).isEqualTo(2);
        assertThat(second.revealed().name()).isEqualTo("Approach");

        // Taking hints never ends the attempt or counts against it.
        Attempt reloaded = attempts.findById(started.id()).orElseThrow();
        assertThat(reloaded.hintsTaken()).isEqualTo(2);
        assertThat(reloaded.outcome()).isEqualTo(AttemptOutcome.IN_PROGRESS);
    }

    @Test
    void takingAHintPastTheLastRungRevealsNothingMoreAndDoesNotOvercount() {
        Attempt started = service.start("pair-in-any-order");
        service.takeHint(started.id());
        service.takeHint(started.id());
        service.takeHint(started.id());

        HintResult beyond = service.takeHint(started.id());
        assertThat(beyond.revealed()).isNull();
        assertThat(beyond.rungsTaken()).isEqualTo(3);
        assertThat(attempts.findById(started.id()).orElseThrow().hintsTaken()).isEqualTo(3);
    }

    @Test
    void aCodeRunRecordsItsRuntimeAndANoCodeRunRecordsZero() {
        Attempt conceptAttempt = service.start("concept-demo");
        service.submit(conceptAttempt.id(), "B");
        assertThat(submissions.findByAttempt(conceptAttempt.id()))
                .singleElement()
                .satisfies(s -> assertThat(s.runtimeMillis()).isZero());

        Attempt pairAttempt = service.start("pair-in-any-order");
        service.submit(pairAttempt.id(), Fixtures.PAIR_SOLUTION);
        assertThat(submissions.findByAttempt(pairAttempt.id()))
                .singleElement()
                // A forked-JVM run takes real wall-clock time; never negative, and
                // recorded so history and the schedulers can read it.
                .satisfies(s -> assertThat(s.runtimeMillis()).isGreaterThanOrEqualTo(0));
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
    void abandoningASolvedAttemptIsRejectedAndLeavesItSolved() {
        Attempt started = service.start("concept-demo");
        service.submit(started.id(), "B");

        UUID id = started.id();
        assertThatThrownBy(() -> service.abandon(id))
                .isInstanceOf(IllegalAttemptStateException.class);

        // A racing abandon can never clobber a sitting that has been solved.
        assertThat(attempts.findById(id).orElseThrow().outcome())
                .isEqualTo(AttemptOutcome.SOLVED);
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
