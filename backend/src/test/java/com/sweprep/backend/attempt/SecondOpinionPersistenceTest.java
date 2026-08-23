package com.sweprep.backend.attempt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.sweprep.backend.advisor.ComplexityAdvisor;
import com.sweprep.backend.advisor.ModelComplexityReading;
import com.sweprep.backend.exercise.Complexity;
import com.sweprep.backend.exercise.ContentCatalog;
import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.ExerciseCatalog;
import com.sweprep.backend.testsupport.Fixtures;
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
 * Proves the LLM complexity second opinion (issue #83) end to end against a real,
 * disposable Postgres, with the model call itself replaced by a fake {@link
 * ComplexityAdvisor} - the seam the ticket asks for, so nothing here ever depends on
 * a live API call. Covers the request order (a claim must already be recorded),
 * that a fresh model reading is compared against the actually-recorded claim and
 * measurement, and that nothing is ever persisted from the result - the hard
 * boundary that keeps this advisory-only by construction, not just by convention.
 */
@SpringBootTest
@Testcontainers
@Transactional
class SecondOpinionPersistenceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private AttemptService service;

    @Autowired
    private AttemptRepository attempts;

    @MockitoBean
    private ExerciseCatalog catalog;

    // FileExerciseCatalog is one bean implementing both catalog seams; mocking ExerciseCatalog
    // replaces it, so the wider ContentCatalog (LessonController's dependency) must be supplied
    // too or the whole context fails to load.
    @MockitoBean
    private ContentCatalog contentCatalog;

    @MockitoBean
    private ComplexityAdvisor advisor;

    private Exercise complexity;
    private Exercise concept;

    @BeforeEach
    void setUp() {
        complexity = Fixtures.complexityChallenge();
        concept = Fixtures.concept();
        when(catalog.byId("complexity-demo")).thenReturn(Optional.of(complexity));
        when(catalog.byId("concept-demo")).thenReturn(Optional.of(concept));
    }

    private UUID solveAndClaim(Complexity claimedTime) {
        Attempt started = service.start("complexity-demo");
        service.submit(started.id(), Fixtures.COMPLEXITY_LINEAR_SOLUTION);
        service.claimComplexity(started.id(), new ComplexityClaim(claimedTime, claimedTime));
        return started.id();
    }

    @Test
    void whenTheAdvisorIsUnavailableTheFeatureIsAbsentNotBroken() {
        when(advisor.available()).thenReturn(false);
        assertThat(service.modelOpinionAvailable()).isFalse();

        UUID attemptId = solveAndClaim(Complexity.LINEAR);

        assertThatThrownBy(() -> service.secondOpinion(attemptId))
                .isInstanceOf(InvalidAttemptRequestException.class)
                .hasMessageContaining("No model complexity advisor is configured");
    }

    @Test
    void whenEveryVoiceAgreesTheResultIsQuietConfirmation() {
        when(advisor.available()).thenReturn(true);
        when(advisor.read(any(), any(), any()))
                .thenReturn(new ModelComplexityReading(Complexity.LINEAR, "It's a single linear scan."));
        assertThat(service.modelOpinionAvailable()).isTrue();

        UUID attemptId = solveAndClaim(Complexity.LINEAR);

        ModelOpinionResult result = service.secondOpinion(attemptId);

        assertThat(result.modelTime()).isEqualTo(Complexity.LINEAR);
        assertThat(result.modelReasoning()).isEqualTo("It's a single linear scan.");
        assertThat(result.disagreement().agreement()).isTrue();
        assertThat(result.disagreement().prompt()).isNull();
    }

    @Test
    void aModelMisreadingAgainstAnAccurateClaimProducesADisagreementPrompt() {
        when(advisor.available()).thenReturn(true);
        // The model misreads a genuinely linear solution as quadratic - a claim/model
        // disagreement the learner should be prompted to resolve.
        when(advisor.read(any(), any(), any()))
                .thenReturn(new ModelComplexityReading(Complexity.QUADRATIC, "Looks like nested iteration."));

        UUID attemptId = solveAndClaim(Complexity.LINEAR);

        ModelOpinionResult result = service.secondOpinion(attemptId);

        assertThat(result.disagreement().agreement()).isFalse();
        assertThat(result.disagreement().prompt())
                .contains("You claimed O(n)")
                .contains("model reads this as O(n²)");
    }

    @Test
    void aSecondOpinionBeforeClaimingComplexityIsRejected() {
        when(advisor.available()).thenReturn(true);
        Attempt started = service.start("complexity-demo");
        service.submit(started.id(), Fixtures.COMPLEXITY_LINEAR_SOLUTION);
        // Solved, but complexity was never claimed.

        UUID attemptId = started.id();
        assertThatThrownBy(() -> service.secondOpinion(attemptId))
                .isInstanceOf(InvalidAttemptRequestException.class)
                .hasMessageContaining("no complexity claim recorded");
    }

    @Test
    void aSecondOpinionBeforeSolvingIsRejected() {
        when(advisor.available()).thenReturn(true);
        Attempt started = service.start("complexity-demo");
        // Never submitted - still IN_PROGRESS.

        UUID attemptId = started.id();
        assertThatThrownBy(() -> service.secondOpinion(attemptId))
                .isInstanceOf(IllegalAttemptStateException.class);
    }

    @Test
    void aSecondOpinionOnAnExerciseWithNoComplexityCheckIsRejected() {
        when(advisor.available()).thenReturn(true);
        Attempt started = service.start("concept-demo");
        service.submit(started.id(), "B");
        // concept-demo carries no complexity check, so no claim can ever be recorded.

        UUID attemptId = started.id();
        assertThatThrownBy(() -> service.secondOpinion(attemptId))
                .isInstanceOf(InvalidAttemptRequestException.class)
                .hasMessageContaining("no complexity claim recorded");
    }

    @Test
    void theResultIsNeverPersistedAnywhereOnTheAttempt() {
        when(advisor.available()).thenReturn(true);
        when(advisor.read(any(), any(), any()))
                .thenReturn(new ModelComplexityReading(Complexity.QUADRATIC, "Nested iteration."));

        UUID attemptId = solveAndClaim(Complexity.LINEAR);
        Attempt before = attempts.findById(attemptId).orElseThrow();

        service.secondOpinion(attemptId);

        // Nothing about the attempt row changed - there is no column a model reading
        // could ever have written into. Advisory only, by construction.
        Attempt after = attempts.findById(attemptId).orElseThrow();
        assertThat(after).isEqualTo(before);
    }
}
