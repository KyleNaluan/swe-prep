package com.sweprep.backend.web;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sweprep.backend.attempt.Attempt;
import com.sweprep.backend.attempt.AttemptNotFoundException;
import com.sweprep.backend.attempt.AttemptOutcome;
import com.sweprep.backend.attempt.AttemptService;
import com.sweprep.backend.attempt.AttemptWithCount;
import com.sweprep.backend.attempt.ComplexityClaimResult;
import com.sweprep.backend.attempt.ExplanationResult;
import com.sweprep.backend.attempt.HintResult;
import com.sweprep.backend.attempt.IllegalAttemptStateException;
import com.sweprep.backend.attempt.InvalidAttemptRequestException;
import com.sweprep.backend.attempt.RevealResult;
import com.sweprep.backend.attempt.SelfCheckRating;
import com.sweprep.backend.attempt.SelfCheckReveal;
import com.sweprep.backend.attempt.SelfRating;
import com.sweprep.backend.attempt.Submission;
import com.sweprep.backend.attempt.SubmissionOutcome;
import com.sweprep.backend.attempt.SubmitResult;
import com.sweprep.backend.exercise.Hint;
import com.sweprep.backend.grader.FailingCase;
import com.fasterxml.jackson.databind.node.IntNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Proves the attempt lifecycle over the HTTP wire with a mocked service: a sitting is
 * opened, a submission returns its verdict, history is listed, and the two failure
 * shapes map to the right status (unknown attempt → 404, an already-ended attempt →
 * 409). The service's own behaviour is proven against a real database in
 * {@code AttemptPersistenceTest}.
 */
@WebMvcTest(AttemptController.class)
class AttemptControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper mapper;

    @MockitoBean
    private AttemptService service;

    private static Attempt attempt(UUID id, AttemptOutcome outcome) {
        return new Attempt(
                id,
                UUID.randomUUID(),
                "two-sum",
                "Two Sum",
                "algorithms",
                "CHALLENGE",
                outcome,
                Instant.parse("2026-08-06T10:00:00Z"),
                outcome == AttemptOutcome.IN_PROGRESS ? null : Instant.parse("2026-08-06T10:05:00Z"),
                0,
                false,
                null,
                false,
                null,
                null,
                null);
    }

    @Test
    void startsAnAttempt() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.start("two-sum")).thenReturn(attempt(id, AttemptOutcome.IN_PROGRESS));

        mockMvc.perform(post("/api/attempts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new StartAttemptRequest("two-sum"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.exerciseId").value("two-sum"))
                .andExpect(jsonPath("$.outcome").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.submissionCount").value(0));
    }

    @Test
    void submittingReturnsTheVerdict() throws Exception {
        UUID id = UUID.randomUUID();
        Submission submission = new Submission(
                UUID.randomUUID(), id, Instant.now(), "class Solution {}",
                SubmissionOutcome.FAILED, 3, 4, "", 12L);
        // A wrong answer here carries no explanation (this check has none).
        when(service.submit(eq(id), any())).thenReturn(new SubmitResult(submission, null, false));

        mockMvc.perform(post("/api/attempts/" + id + "/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new RunRequest("class Solution {}"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("FAILED"))
                .andExpect(jsonPath("$.passed").value(3))
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.runtimeMillis").value(12))
                // A failing verdict discloses only the count - no input/expected/actual.
                .andExpect(jsonPath("$.input").doesNotExist())
                .andExpect(jsonPath("$.expected").doesNotExist())
                .andExpect(jsonPath("$.actual").doesNotExist())
                // No explanation on this check, so the field is omitted entirely.
                .andExpect(jsonPath("$.explanation").doesNotExist());
    }

    @Test
    void aWrongAnswerDisclosesTheExplanationAutomatically() throws Exception {
        UUID id = UUID.randomUUID();
        Submission submission = new Submission(
                UUID.randomUUID(), id, Instant.now(), "B",
                SubmissionOutcome.FAILED, 0, 1, "", 0L);
        when(service.submit(eq(id), any()))
                .thenReturn(new SubmitResult(submission, "Because B holds in every case.", false));

        mockMvc.perform(post("/api/attempts/" + id + "/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new RunRequest("A"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("FAILED"))
                .andExpect(jsonPath("$.explanation").value("Because B holds in every case."));
    }

    @Test
    void requestingTheExplanationReturnsItAndRecordsTheRequest() throws Exception {
        UUID id = UUID.randomUUID();
        Attempt requested = attempt(id, AttemptOutcome.SOLVED).withExplanationRequested();
        AttemptWithCount withCount = new AttemptWithCount(requested, 1);
        when(service.requestExplanation(id))
                .thenReturn(new ExplanationResult(withCount, "Because B holds in every case."));

        mockMvc.perform(post("/api/attempts/" + id + "/explanation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.explanation").value("Because B holds in every case."))
                .andExpect(jsonPath("$.attempt.explanationRequested").value(true));
    }

    @Test
    void listsHistoryWithSubmissionCounts() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.history())
                .thenReturn(List.of(new AttemptWithCount(attempt(id, AttemptOutcome.SOLVED), 2)));

        mockMvc.perform(get("/api/attempts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].outcome").value("SOLVED"))
                .andExpect(jsonPath("$[0].submissionCount").value(2));
    }

    @Test
    void abandoningAnUnknownAttemptIsNotFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.abandon(id)).thenThrow(new AttemptNotFoundException("No attempt with id " + id));

        mockMvc.perform(post("/api/attempts/" + id + "/abandon"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("No attempt")));
    }

    @Test
    void takingAHintReturnsTheRungAndRecordsItOnTheAttempt() throws Exception {
        UUID id = UUID.randomUUID();
        AttemptWithCount withCount = new AttemptWithCount(attempt(id, AttemptOutcome.IN_PROGRESS), 1);
        when(service.takeHint(id)).thenReturn(
                new HintResult(withCount, 1, 3, new Hint("Pattern", "It is a sliding window.")));

        mockMvc.perform(post("/api/attempts/" + id + "/hints"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rungsTaken").value(1))
                .andExpect(jsonPath("$.totalRungs").value(3))
                .andExpect(jsonPath("$.name").value("Pattern"))
                .andExpect(jsonPath("$.body").value("It is a sliding window."))
                .andExpect(jsonPath("$.attempt.hintsTaken").value(0));
    }

    @Test
    void revealingReturnsTheFailingCaseAndRecordsTheReveal() throws Exception {
        UUID id = UUID.randomUUID();
        AttemptWithCount withCount = new AttemptWithCount(attempt(id, AttemptOutcome.IN_PROGRESS), 1);
        FailingCase failing = new FailingCase(
                IntNode.valueOf(3), IntNode.valueOf(9), IntNode.valueOf(6), null);
        when(service.revealFailingCase(eq(id), any(), any()))
                .thenReturn(new RevealResult(withCount, failing));

        mockMvc.perform(post("/api/attempts/" + id + "/reveal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new RevealRequest("class Solution {}", "off-by-one on the last index"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failingCase.input").value(3))
                .andExpect(jsonPath("$.failingCase.expected").value(9))
                .andExpect(jsonPath("$.failingCase.actual").value(6))
                .andExpect(jsonPath("$.attempt.failingCaseRevealed").value(false));
    }

    @Test
    void revealingWithNoFailingCaseOmitsIt() throws Exception {
        UUID id = UUID.randomUUID();
        AttemptWithCount withCount = new AttemptWithCount(attempt(id, AttemptOutcome.IN_PROGRESS), 0);
        when(service.revealFailingCase(eq(id), any(), any()))
                .thenReturn(new RevealResult(withCount, null));

        mockMvc.perform(post("/api/attempts/" + id + "/reveal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new RevealRequest("x", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failingCase").doesNotExist());
    }

    @Test
    void submittingToAnEndedAttemptConflicts() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.submit(eq(id), any()))
                .thenThrow(new IllegalAttemptStateException("Attempt " + id + " has already ended (SOLVED)"));

        mockMvc.perform(post("/api/attempts/" + id + "/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new RunRequest("x"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("already ended")));
    }

    @Test
    void revealingASelfCheckReturnsTheModelAnswerAndTheSubmissionToRate() throws Exception {
        UUID id = UUID.randomUUID();
        UUID submissionId = UUID.randomUUID();
        Submission committed = new Submission(
                submissionId, id, Instant.now(), "my explanation",
                SubmissionOutcome.SELF_RATED, 0, 0, "", 0L);
        when(service.revealSelfCheck(eq(id), any()))
                .thenReturn(new SelfCheckReveal(committed, "The model answer."));

        mockMvc.perform(post("/api/attempts/" + id + "/self-check/reveal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new SelfCheckRevealRequest("my explanation"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submissionId").value(submissionId.toString()))
                .andExpect(jsonPath("$.modelAnswer").value("The model answer."));
    }

    @Test
    void ratingASelfCheckRecordsItAndEndsExplained() throws Exception {
        UUID id = UUID.randomUUID();
        UUID submissionId = UUID.randomUUID();
        AttemptWithCount withCount = new AttemptWithCount(attempt(id, AttemptOutcome.EXPLAINED), 1);
        when(service.rateSelfCheck(eq(id), eq(submissionId), eq(SelfRating.NAILED_IT)))
                .thenReturn(new SelfCheckRating(withCount, SelfRating.NAILED_IT));

        mockMvc.perform(post("/api/attempts/" + id + "/self-check/rating")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new SelfCheckRatingRequest(submissionId, SelfRating.NAILED_IT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value("NAILED_IT"))
                .andExpect(jsonPath("$.attempt.outcome").value("EXPLAINED"));
    }

    @Test
    void revealingANonSelfCheckItemIsABadRequest() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.revealSelfCheck(eq(id), any()))
                .thenThrow(new InvalidAttemptRequestException(
                        "Exercise 'x' is not a self-check item"));

        mockMvc.perform(post("/api/attempts/" + id + "/self-check/reveal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new SelfCheckRevealRequest("text"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error")
                        .value(org.hamcrest.Matchers.containsString("not a self-check")));
    }

    @Test
    void aServerSideIllegalArgumentIsNotEchoedAsABadRequest() {
        UUID id = UUID.randomUUID();
        when(service.revealSelfCheck(eq(id), any()))
                .thenThrow(new IllegalArgumentException(
                        "No enum constant com.sweprep.backend.attempt.SubmissionOutcome.BOGUS"));

        assertThatThrownBy(() -> mockMvc.perform(post("/api/attempts/" + id + "/self-check/reveal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new SelfCheckRevealRequest("text")))))
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    // --- Complexity self-report (issue #17) ----------------------------------------

    @Test
    void claimingComplexityRevealsTheTargetAndReportsAConsistentMeasurement() throws Exception {
        UUID id = UUID.randomUUID();
        Attempt recorded = attempt(id, AttemptOutcome.SOLVED)
                .withComplexity("time=LINEAR;space=CONSTANT", "LINEAR:1.02", true);
        AttemptWithCount withCount = new AttemptWithCount(recorded, 1);
        when(service.claimComplexity(eq(id), any())).thenReturn(new ComplexityClaimResult(
                withCount,
                com.sweprep.backend.exercise.Complexity.LINEAR,
                com.sweprep.backend.exercise.Complexity.CONSTANT,
                new com.sweprep.backend.complexity.MeasurementOutcome.Conclusive(
                        com.sweprep.backend.complexity.ComplexityBucket.LINEAR, 1.02)));

        mockMvc.perform(post("/api/attempts/" + id + "/complexity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ComplexityClaimRequest(
                                com.sweprep.backend.exercise.Complexity.LINEAR,
                                com.sweprep.backend.exercise.Complexity.CONSTANT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetTime").value("LINEAR"))
                .andExpect(jsonPath("$.targetSpace").value("CONSTANT"))
                .andExpect(jsonPath("$.status").value("CONSISTENT"))
                // Never worded as flatly "correct" - the wire status is the coarse,
                // honest vocabulary; the editor's own copy layers the "consistent with
                // your claim" wording on top of this.
                .andExpect(jsonPath("$.detail").doesNotExist())
                .andExpect(jsonPath("$.attempt.complexityClaimCorrect").value(true));
    }

    @Test
    void aContradictedClaimIsReportedAsContradictedNeverSilentlyPassed() throws Exception {
        UUID id = UUID.randomUUID();
        Attempt recorded = attempt(id, AttemptOutcome.SOLVED)
                .withComplexity("time=LINEAR;space=CONSTANT", "QUADRATIC:2.01", false);
        AttemptWithCount withCount = new AttemptWithCount(recorded, 1);
        when(service.claimComplexity(eq(id), any())).thenReturn(new ComplexityClaimResult(
                withCount,
                com.sweprep.backend.exercise.Complexity.LINEAR,
                com.sweprep.backend.exercise.Complexity.CONSTANT,
                new com.sweprep.backend.complexity.MeasurementOutcome.Conclusive(
                        com.sweprep.backend.complexity.ComplexityBucket.QUADRATIC, 2.01)));

        mockMvc.perform(post("/api/attempts/" + id + "/complexity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ComplexityClaimRequest(
                                com.sweprep.backend.exercise.Complexity.LINEAR,
                                com.sweprep.backend.exercise.Complexity.CONSTANT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONTRADICTED"))
                .andExpect(jsonPath("$.attempt.complexityClaimCorrect").value(false));
    }

    @Test
    void anInconclusiveMeasurementNeverAssertsCorrectnessEitherWay() throws Exception {
        UUID id = UUID.randomUUID();
        Attempt recorded = attempt(id, AttemptOutcome.SOLVED)
                .withComplexity("time=LINEAR;space=CONSTANT", "INCONCLUSIVE", null);
        AttemptWithCount withCount = new AttemptWithCount(recorded, 1);
        when(service.claimComplexity(eq(id), any())).thenReturn(new ComplexityClaimResult(
                withCount,
                com.sweprep.backend.exercise.Complexity.LINEAR,
                com.sweprep.backend.exercise.Complexity.CONSTANT,
                new com.sweprep.backend.complexity.MeasurementOutcome.Inconclusive(
                        "measured growth sits between two complexity classes and cannot be "
                                + "confidently classified")));

        mockMvc.perform(post("/api/attempts/" + id + "/complexity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ComplexityClaimRequest(
                                com.sweprep.backend.exercise.Complexity.LINEAR,
                                com.sweprep.backend.exercise.Complexity.CONSTANT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INCONCLUSIVE"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("cannot be")))
                .andExpect(jsonPath("$.attempt.complexityClaimCorrect").doesNotExist());
    }

    @Test
    void claimingComplexityOnAnUnsolvedAttemptConflicts() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.claimComplexity(eq(id), any())).thenThrow(new IllegalAttemptStateException(
                "Attempt " + id + " is not solved yet; complexity is claimed only after a passing submission"));

        mockMvc.perform(post("/api/attempts/" + id + "/complexity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ComplexityClaimRequest(
                                com.sweprep.backend.exercise.Complexity.LINEAR,
                                com.sweprep.backend.exercise.Complexity.CONSTANT))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("not solved")));
    }

    @Test
    void claimingComplexityOnAnExerciseWithNoCheckIsABadRequest() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.claimComplexity(eq(id), any())).thenThrow(new InvalidAttemptRequestException(
                "Exercise 'x' has no complexity target to claim against"));

        mockMvc.perform(post("/api/attempts/" + id + "/complexity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ComplexityClaimRequest(
                                com.sweprep.backend.exercise.Complexity.LINEAR,
                                com.sweprep.backend.exercise.Complexity.CONSTANT))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error")
                        .value(org.hamcrest.Matchers.containsString("no complexity target")));
    }
}
