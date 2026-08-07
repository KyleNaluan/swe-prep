package com.sweprep.backend.reps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sweprep.backend.attempt.AttemptRepository;
import com.sweprep.backend.attempt.CurrentUser;
import com.sweprep.backend.attempt.SubmissionRepository;
import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.ExerciseCatalog;
import com.sweprep.backend.exercise.Family;
import com.sweprep.backend.testsupport.Fixtures;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Proves the wiring the pure selector cannot: that {@link WarmupService} feeds the
 * selector the current user's attempted problems (so gating is real) and turns an empty
 * configured family list into "no restriction" (the family setting is #40). The
 * selection rules themselves are proven in {@link WarmupSelectorTest}.
 */
class WarmupServiceTest {

    private final UUID user = UUID.randomUUID();

    @Test
    void gatesDerivedRepsOnTheCurrentUsersAttemptedProblems() {
        AttemptRepository attempts = mock(AttemptRepository.class);
        when(attempts.attemptedExerciseIds(user)).thenReturn(Set.of("sorted-pair-sum"));

        WarmupService service = service(
                attempts,
                new RepProperties(8, 2, List.of()),
                Fixtures.patternIdRep(), // cold
                Fixtures.complexityRep(), // derivedFrom "sorted-pair-sum" - now unlocked
                Fixtures.spotBugRep()); // derivedFrom "max-element" - still gated

        assertThat(service.warmup())
                .extracting(Exercise::id)
                .contains("rep-pattern-id", "rep-complexity")
                .doesNotContain("rep-spot-bug");
    }

    @Test
    void anEmptyActiveFamilyListMeansNoFamilyRestriction() {
        AttemptRepository attempts = mock(AttemptRepository.class);
        when(attempts.attemptedExerciseIds(user)).thenReturn(Set.of());

        // patternIdRep is tagged CORE; a DATA-only rep should also be served when the
        // configured active list is empty (every family treated as active until #40).
        Exercise dataRep = dataFamilyRep();
        WarmupService service =
                service(attempts, new RepProperties(8, 2, List.of()), Fixtures.patternIdRep(), dataRep);

        assertThat(service.warmup()).extracting(Exercise::id).contains("rep-pattern-id", "data-rep");
    }

    @Test
    void aConfiguredActiveFamilyListSuppressesInactiveFamilies() {
        AttemptRepository attempts = mock(AttemptRepository.class);
        when(attempts.attemptedExerciseIds(user)).thenReturn(Set.of());

        // Only BACKEND active: the DATA-only rep is suppressed, the CORE rep stays.
        WarmupService service = service(
                attempts,
                new RepProperties(8, 2, List.of(Family.BACKEND)),
                Fixtures.patternIdRep(),
                dataFamilyRep());

        assertThat(service.warmup())
                .extracting(Exercise::id)
                .contains("rep-pattern-id")
                .doesNotContain("data-rep");
    }

    private WarmupService service(
            AttemptRepository attempts, RepProperties properties, Exercise... catalog) {
        CurrentUser currentUser = mock(CurrentUser.class);
        when(currentUser.id()).thenReturn(user);
        ExerciseCatalog exercises = Fixtures.catalogOf(catalog);
        // No recorded wrong answers here: these tests prove the family/gating wiring, so
        // the confusion relation is empty and never reorders the set.
        SubmissionRepository submissions = mock(SubmissionRepository.class);
        when(submissions.failedResponses(user)).thenReturn(List.of());
        ConfusionPairService confusionPairs = new ConfusionPairService(submissions, exercises);
        return new WarmupService(exercises, attempts, confusionPairs, currentUser, properties);
    }

    private static Exercise dataFamilyRep() {
        Exercise core = Fixtures.patternIdRep();
        return new Exercise(
                "data-rep",
                "Data rep",
                core.statement(),
                core.domain(),
                core.topics(),
                core.difficulty(),
                core.form(),
                core.response(),
                core.grading(),
                core.hints(),
                core.explanation(),
                List.of(Family.DATA),
                core.stability(),
                core.reviewed(),
                null);
    }
}
