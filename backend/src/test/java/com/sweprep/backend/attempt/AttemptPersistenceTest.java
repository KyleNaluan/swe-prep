package com.sweprep.backend.attempt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.when;

import com.sweprep.backend.exercise.Complexity;
import com.sweprep.backend.exercise.ContentCatalog;
import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.ExerciseCatalog;
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

    @Autowired
    private CurrentUser currentUser;

    @MockitoBean
    private ExerciseCatalog catalog;

    // Mocking ExerciseCatalog replaces the shared FileExerciseCatalog bean, so the wider
    // ContentCatalog (LessonController's dependency) must be supplied for the context to load.
    @MockitoBean
    private ContentCatalog contentCatalog;

    private Exercise concept;
    private Exercise pair;
    private Exercise predict;
    private Exercise complexity;
    private Exercise complexityNoGenerator;

    @BeforeEach
    void setUp() {
        concept = Fixtures.concept();
        pair = Fixtures.pairInAnyOrder();
        // predict-number carries no explanation - the sibling to concept-demo, which does.
        predict = Fixtures.predictNumber();
        complexity = Fixtures.complexityChallenge();
        complexityNoGenerator = Fixtures.complexityChallengeWithNoGenerator();
        when(catalog.byId("concept-demo")).thenReturn(Optional.of(concept));
        when(catalog.byId("pair-in-any-order")).thenReturn(Optional.of(pair));
        when(catalog.byId("predict-number")).thenReturn(Optional.of(predict));
        when(catalog.byId("complexity-demo")).thenReturn(Optional.of(complexity));
        when(catalog.byId("complexity-demo-no-generator")).thenReturn(Optional.of(complexityNoGenerator));
        when(catalog.byId("missing")).thenReturn(Optional.empty());
    }

    @Test
    void aPassingSubmissionSolvesTheAttemptAndBothPersist() {
        Attempt started = service.start("concept-demo");
        SubmitResult result = service.submit(started.id(), "B");
        Submission submission = result.submission();

        assertThat(submission.outcome()).isEqualTo(SubmissionOutcome.PASSED);
        // A passing answer withholds the explanation - it is offered on request instead.
        assertThat(result.explanation()).isNull();

        // Re-read from the database, not the returned object, to prove durability.
        Attempt reloaded = attempts.findById(started.id()).orElseThrow();
        assertThat(reloaded.outcome()).isEqualTo(AttemptOutcome.SOLVED);
        assertThat(reloaded.endedAt()).isNotNull();
        assertThat(reloaded.exerciseTitle()).isEqualTo("Concept Demo");
        assertThat(reloaded.form()).isEqualTo("REP");

        List<Submission> stored = submissions.findByAttempt(started.id());
        assertThat(stored).singleElement().satisfies(s -> {
            assertThat(s.response()).isEqualTo("B");
            assertThat(s.outcome()).isEqualTo(SubmissionOutcome.PASSED);
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
    void aWrongAnswerDisclosesTheExplanationAutomaticallyAndRecordsNoRequest() {
        Attempt started = service.start("concept-demo");
        SubmitResult result = service.submit(started.id(), "A");

        // The explanation is shown automatically on a wrong answer (issue #51)...
        assertThat(result.submission().outcome()).isEqualTo(SubmissionOutcome.FAILED);
        assertThat(result.explanation()).isEqualTo(Fixtures.CONCEPT_EXPLANATION);

        // ...but that automatic disclosure is not a request, so nothing is recorded and
        // the sitting stays open and unpenalised.
        Attempt reloaded = attempts.findById(started.id()).orElseThrow();
        assertThat(reloaded.explanationRequested()).isFalse();
        assertThat(reloaded.hintsTaken()).isZero();
        assertThat(reloaded.outcome()).isEqualTo(AttemptOutcome.IN_PROGRESS);
    }

    @Test
    void requestingTheExplanationRecordsADistinctSignalAndNeverPenalises() {
        Attempt started = service.start("concept-demo");
        // Correct answer solves the sitting; the explanation is then one keystroke away.
        service.submit(started.id(), "B");

        ExplanationResult result = service.requestExplanation(started.id());
        assertThat(result.explanation()).isEqualTo(Fixtures.CONCEPT_EXPLANATION);

        Attempt reloaded = attempts.findById(started.id()).orElseThrow();
        assertThat(reloaded.explanationRequested()).isTrue();
        // Recorded distinctly from taking a hint, and it neither reopens nor penalises.
        assertThat(reloaded.hintsTaken()).isZero();
        assertThat(reloaded.outcome()).isEqualTo(AttemptOutcome.SOLVED);
    }

    @Test
    void aCheckWithNoExplanationDisclosesNothingButStillRecordsTheRequest() {
        Attempt started = service.start("predict-number");
        // Wrong answer: still nothing to auto-disclose, since this check has no explanation.
        SubmitResult wrong = service.submit(started.id(), "7");
        assertThat(wrong.submission().outcome()).isEqualTo(SubmissionOutcome.FAILED);
        assertThat(wrong.explanation()).isNull();

        // Giving up then reading why is a legitimate terminal path: the explanation is
        // only honoured once the sitting has ended.
        service.abandon(started.id());
        ExplanationResult result = service.requestExplanation(started.id());
        assertThat(result.explanation()).isNull();
        // The solver did ask, so the request is recorded even with nothing to show.
        assertThat(attempts.findById(started.id()).orElseThrow().explanationRequested()).isTrue();
    }

    @Test
    void requestingTheExplanationOnAnOpenAttemptIsRejected() {
        Attempt started = service.start("concept-demo");
        // Withhold-by-default: the API, not just the editor, refuses to disclose the
        // explanation before the sitting ends, so it can never be read pre-answer.
        assertThatThrownBy(() -> service.requestExplanation(started.id()))
                .isInstanceOf(IllegalAttemptStateException.class);
        assertThat(attempts.findById(started.id()).orElseThrow().explanationRequested()).isFalse();
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
    void wrongChoiceSubmissionsAreQueryableForConfusionDerivation() {
        Attempt started = service.start("concept-demo");
        service.submit(started.id(), "A"); // wrong: FAILED, the confusion signal (issue #39)
        service.submit(started.id(), "B"); // right: PASSED, not a picked distractor

        // Only the wrong pick surfaces, as (exercise id, chosen distractor), so the
        // confusion relation can be derived from the picked response with no new column.
        assertThat(submissions.failedResponses(currentUser.id()))
                .extracting(
                        SubmissionRepository.FailedResponse::exerciseId,
                        SubmissionRepository.FailedResponse::response)
                .containsExactly(tuple("concept-demo", "A"));
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

    // --- Complexity self-report (issue #17) ---------------------------------------

    @Test
    void claimingComplexityRecordsTheClaimAndRevealsTheAuthoredTarget() {
        Attempt started = service.start("complexity-demo");
        service.submit(started.id(), Fixtures.COMPLEXITY_LINEAR_SOLUTION);

        ComplexityClaimResult result =
                service.claimComplexity(started.id(), new ComplexityClaim(Complexity.LINEAR, Complexity.LINEAR));

        // The target is revealed here - and only here.
        assertThat(result.targetTime()).isEqualTo(Complexity.LINEAR);
        assertThat(result.targetSpace()).isEqualTo(Complexity.LINEAR);
        // Never asserted as flatly "correct" - a false contradiction is the one thing
        // that must never happen; inconclusive is an acceptable, honest outcome.
        assertThat(result.attempt().attempt().complexityClaimCorrect()).isNotEqualTo(Boolean.FALSE);

        Attempt reloaded = attempts.findById(started.id()).orElseThrow();
        assertThat(reloaded.complexityClaim()).isEqualTo("time=LINEAR;space=LINEAR");
        assertThat(reloaded.measuredComplexity()).isNotNull();
    }

    @Test
    void aQuadraticSolutionClaimingLinearIsReliablyCaught() {
        Attempt started = service.start("complexity-demo");
        // Passes the same correctness case as the linear solution - only its scaling differs.
        service.submit(started.id(), Fixtures.COMPLEXITY_QUADRATIC_SOLUTION);

        ComplexityClaimResult result =
                service.claimComplexity(started.id(), new ComplexityClaim(Complexity.LINEAR, Complexity.LINEAR));

        assertThat(result.measurement()).isInstanceOfSatisfying(
                com.sweprep.backend.complexity.MeasurementOutcome.Conclusive.class,
                conclusive -> assertThat(conclusive.bucket())
                        .isEqualTo(com.sweprep.backend.complexity.ComplexityBucket.QUADRATIC));
        assertThat(result.attempt().attempt().complexityClaimCorrect()).isFalse();

        Attempt reloaded = attempts.findById(started.id()).orElseThrow();
        assertThat(reloaded.complexityClaimCorrect()).isFalse();
        assertThat(reloaded.measuredComplexity()).startsWith("QUADRATIC");
    }

    @Test
    void anExerciseWithNoInputGeneratorStillAsksAndRevealsButSkipsMeasurement() {
        Attempt started = service.start("complexity-demo-no-generator");
        service.submit(started.id(), Fixtures.COMPLEXITY_LINEAR_SOLUTION);

        ComplexityClaimResult result = service.claimComplexity(
                started.id(), new ComplexityClaim(Complexity.LINEAR, Complexity.LINEAR));

        // The target is still asked for and revealed...
        assertThat(result.targetTime()).isEqualTo(Complexity.LINEAR);
        // ...but measurement is skipped entirely, without error, and nothing is asserted.
        assertThat(result.measurement()).isInstanceOf(
                com.sweprep.backend.complexity.MeasurementOutcome.Skipped.class);
        assertThat(result.attempt().attempt().complexityClaimCorrect()).isNull();

        Attempt reloaded = attempts.findById(started.id()).orElseThrow();
        assertThat(reloaded.complexityClaim()).isEqualTo("time=LINEAR;space=LINEAR");
        assertThat(reloaded.measuredComplexity()).isNull();
        assertThat(reloaded.complexityClaimCorrect()).isNull();
    }

    @Test
    void claimingComplexityBeforeSolvingIsRejected() {
        Attempt started = service.start("complexity-demo");
        // Never submitted - still IN_PROGRESS, so the claim comes before any pass.

        UUID id = started.id();
        assertThatThrownBy(() -> service.claimComplexity(
                        id, new ComplexityClaim(Complexity.LINEAR, Complexity.LINEAR)))
                .isInstanceOf(IllegalAttemptStateException.class);
    }

    @Test
    void claimingComplexityTwiceIsRejected() {
        Attempt started = service.start("complexity-demo");
        service.submit(started.id(), Fixtures.COMPLEXITY_LINEAR_SOLUTION);
        service.claimComplexity(started.id(), new ComplexityClaim(Complexity.LINEAR, Complexity.LINEAR));

        UUID id = started.id();
        assertThatThrownBy(() -> service.claimComplexity(
                        id, new ComplexityClaim(Complexity.QUADRATIC, Complexity.LINEAR)))
                .isInstanceOf(IllegalAttemptStateException.class);
    }

    @Test
    void claimingComplexityOnAnExerciseWithNoComplexityCheckIsRejected() {
        Attempt started = service.start("concept-demo");
        service.submit(started.id(), "B");

        UUID id = started.id();
        assertThatThrownBy(() -> service.claimComplexity(
                        id, new ComplexityClaim(Complexity.LINEAR, Complexity.LINEAR)))
                .isInstanceOf(InvalidAttemptRequestException.class);
    }
}
