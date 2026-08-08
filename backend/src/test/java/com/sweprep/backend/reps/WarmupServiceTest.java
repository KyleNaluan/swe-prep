package com.sweprep.backend.reps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sweprep.backend.attempt.AttemptRepository;
import com.sweprep.backend.attempt.CurrentUser;
import com.sweprep.backend.attempt.SubmissionRepository;
import com.sweprep.backend.exercise.Content;
import com.sweprep.backend.exercise.ContentCatalog;
import com.sweprep.backend.exercise.Difficulty;
import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.ExerciseCatalog;
import com.sweprep.backend.exercise.Family;
import com.sweprep.backend.exercise.Lesson;
import com.sweprep.backend.role.RoleService;
import com.sweprep.backend.testsupport.Fixtures;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Proves the wiring the pure selector cannot: that {@link WarmupService} feeds the selector the
 * current user's active families (from their role choice, issue #40), attempted problems (so gating
 * is real), and the opted-in reps that survive a family deactivation and that reading a lesson
 * seeds. The selection rules themselves are proven in {@link WarmupSelectorTest}.
 */
class WarmupServiceTest {

    private final UUID user = UUID.randomUUID();

    @Test
    void gatesDerivedRepsOnTheCurrentUsersAttemptedProblems() {
        AttemptRepository attempts = mock(AttemptRepository.class);
        when(attempts.attemptedExerciseIds(user)).thenReturn(Set.of("sorted-pair-sum"));

        WarmupService service = service(
                attempts,
                allFamiliesActive(),
                catalog(),
                Fixtures.patternIdRep(), // cold
                Fixtures.complexityRep(), // derivedFrom "sorted-pair-sum" - now unlocked
                Fixtures.spotBugRep()); // derivedFrom "max-element" - still gated

        assertThat(service.warmup())
                .extracting(Exercise::id)
                .contains("rep-pattern-id", "rep-complexity")
                .doesNotContain("rep-spot-bug");
    }

    @Test
    void anUnsetRoleMeansEveryFamilyIsActive() {
        AttemptRepository attempts = mock(AttemptRepository.class);
        when(attempts.attemptedExerciseIds(user)).thenReturn(Set.of());

        // An unset user gets every selectable family active, so a DATA-only rep is served too.
        WarmupService service =
                service(attempts, allFamiliesActive(), catalog(), Fixtures.patternIdRep(), dataFamilyRep());

        assertThat(service.warmup()).extracting(Exercise::id).contains("rep-pattern-id", "data-rep");
    }

    @Test
    void anActiveRoleSuppressesInactiveFamilies() {
        AttemptRepository attempts = mock(AttemptRepository.class);
        when(attempts.attemptedExerciseIds(user)).thenReturn(Set.of());

        // Only BACKEND active: the DATA-only rep is suppressed, the CORE rep stays.
        WarmupService service = service(
                attempts,
                activeFamilies(Family.BACKEND),
                catalog(),
                Fixtures.patternIdRep(),
                dataFamilyRep());

        assertThat(service.warmup())
                .extracting(Exercise::id)
                .contains("rep-pattern-id")
                .doesNotContain("data-rep");
    }

    @Test
    void readingAnInactiveFamilyLessonSeedsItsChecks() {
        // DATA is inactive, so the DATA rep is normally suppressed. But it is a Check of a Lesson
        // the user has read, so reading opts it into the warm-up (the reachability hinge, #40).
        Exercise dataRep = dataFamilyRep();
        Lesson lesson = lessonSeeding("lesson-data", dataRep.id());
        AttemptRepository attempts = mock(AttemptRepository.class);
        when(attempts.attemptedExerciseIds(user)).thenReturn(Set.of());
        when(attempts.readLessonIds(user)).thenReturn(Set.of("lesson-data"));

        WarmupService service = service(
                attempts,
                activeFamilies(Family.BACKEND),
                catalog(lesson),
                Fixtures.patternIdRep(),
                dataRep);

        assertThat(service.warmup()).extracting(Exercise::id).contains("rep-pattern-id", "data-rep");
    }

    @Test
    void deactivatingAFamilyKeepsAnAlreadyAttemptedRepInTheWarmup() {
        // The criterion most likely to rot silently (issue #40): FRONTEND is now inactive, but the
        // user already attempted this FRONTEND rep, so it is already-due and must stay in the queue -
        // deactivating a family stops new seeding, it does not rip in-flight reviews out.
        Exercise frontendRep = frontendFamilyRep();
        AttemptRepository attempts = mock(AttemptRepository.class);
        when(attempts.attemptedExerciseIds(user)).thenReturn(Set.of(frontendRep.id()));

        WarmupService service = service(
                attempts,
                activeFamilies(Family.BACKEND), // FRONTEND deactivated
                catalog(),
                Fixtures.patternIdRep(),
                frontendRep);

        assertThat(service.warmup())
                .extracting(Exercise::id)
                .contains("rep-pattern-id", "frontend-rep");
    }

    @Test
    void deactivatingAFamilyStopsSeedingARepTheUserNeverTouched() {
        // The other side of the same rule: an untouched inactive-family rep really is suppressed,
        // so the "keeps in-flight" test above is proving the exception, not a filter that never fires.
        Exercise frontendRep = frontendFamilyRep();
        AttemptRepository attempts = mock(AttemptRepository.class);
        when(attempts.attemptedExerciseIds(user)).thenReturn(Set.of());

        WarmupService service = service(
                attempts,
                activeFamilies(Family.BACKEND),
                catalog(),
                Fixtures.patternIdRep(),
                frontendRep);

        assertThat(service.warmup())
                .extracting(Exercise::id)
                .contains("rep-pattern-id")
                .doesNotContain("frontend-rep");
    }

    private RoleService allFamiliesActive() {
        return activeFamilies(com.sweprep.backend.role.RolePreset.selectableFamilies().toArray(new Family[0]));
    }

    private RoleService activeFamilies(Family... families) {
        RoleService roles = mock(RoleService.class);
        when(roles.activeFamilies(user)).thenReturn(Set.of(families));
        return roles;
    }

    private WarmupService service(
            AttemptRepository attempts, RoleService roles, ContentCatalog content, Exercise... catalog) {
        CurrentUser currentUser = mock(CurrentUser.class);
        when(currentUser.id()).thenReturn(user);
        ExerciseCatalog exercises = Fixtures.catalogOf(catalog);
        // No recorded wrong answers here: these tests prove the family/gating wiring, so the
        // confusion relation is empty and never reorders the set.
        SubmissionRepository submissions = mock(SubmissionRepository.class);
        when(submissions.failedResponses(user)).thenReturn(List.of());
        ConfusionPairService confusionPairs = new ConfusionPairService(submissions, exercises);
        return new WarmupService(
                exercises,
                content,
                attempts,
                confusionPairs,
                currentUser,
                roles,
                new RepProperties(8, 2));
    }

    private static ContentCatalog catalog(Lesson... lessons) {
        List<Content> all = List.of(lessons);
        return new ContentCatalog() {
            @Override
            public List<Content> allContent() {
                return all;
            }

            @Override
            public Optional<Content> contentById(String id) {
                return all.stream().filter(c -> c.id().equals(id)).findFirst();
            }
        };
    }

    private static Lesson lessonSeeding(String id, String... checkIds) {
        return new Lesson(
                id,
                "Seeding lesson",
                "A lesson whose reading seeds its checks.",
                "fundamentals",
                List.of("indexes"),
                Difficulty.EASY,
                List.of(checkIds));
    }

    private static Exercise dataFamilyRep() {
        return retag(Fixtures.patternIdRep(), "data-rep", "Data rep", Family.DATA);
    }

    private static Exercise frontendFamilyRep() {
        return retag(Fixtures.patternIdRep(), "frontend-rep", "Frontend rep", Family.FRONTEND);
    }

    private static Exercise retag(Exercise base, String id, String title, Family family) {
        return new Exercise(
                id,
                title,
                base.statement(),
                base.domain(),
                base.topics(),
                base.difficulty(),
                base.form(),
                base.response(),
                base.grading(),
                base.hints(),
                base.explanation(),
                List.of(family),
                base.stability(),
                base.reviewed(),
                null);
    }
}
