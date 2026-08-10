package com.sweprep.backend.challenge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sweprep.backend.attempt.AttemptRepository;
import com.sweprep.backend.attempt.AttemptRepository.ChallengeAttemptRow;
import com.sweprep.backend.attempt.CurrentUser;
import com.sweprep.backend.attempt.SubmissionRepository;
import com.sweprep.backend.exercise.Difficulty;
import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.ExerciseCatalog;
import com.sweprep.backend.exercise.Form;
import com.sweprep.backend.exercise.Grading;
import com.sweprep.backend.exercise.Response;
import com.sweprep.backend.scheduler.ChallengeSchedulerProperties;
import com.sweprep.backend.testsupport.Fixtures;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Proves the wiring the pure {@link com.sweprep.backend.scheduler.ChallengePriority} cannot
 * check for itself: that {@link ChallengeService} reduces raw attempt rows, submission
 * counts, the solved-cold set and first-attempt dates into the right {@link
 * com.sweprep.backend.scheduler.ChallengeCandidate}s. Mirrors {@code RepDueServiceTest}'s
 * mock-based shape; the scoring math itself is proven in isolation by {@code
 * ChallengePriorityTest}.
 */
class ChallengeServiceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-01-10T00:00:00Z");

    @Test
    void aNeverAttemptedChallengeIsSelectedWhenItIsTheOnlyOne() {
        Exercise challenge = minimalChallenge("only");
        ExerciseCatalog catalog = Fixtures.catalogOf(challenge);
        AttemptRepository attempts = mock(AttemptRepository.class);
        when(attempts.challengeReviews(USER)).thenReturn(List.of());
        when(attempts.solvedColdExerciseIds(USER)).thenReturn(Set.of());
        when(attempts.firstChallengeAttemptDates(USER)).thenReturn(Map.of());
        SubmissionRepository submissions = mock(SubmissionRepository.class);
        when(submissions.countsForAttempts(List.of())).thenReturn(Map.of());

        ChallengeService service = service(catalog, attempts, submissions, NOW);

        assertThat(service.selectMain(USER)).contains(challenge);
    }

    @Test
    void aCatalogWithNoChallengesSelectsNothing() {
        Exercise rep = Fixtures.patternIdRep();
        assertThat(rep.form()).isEqualTo(Form.REP);
        ExerciseCatalog catalog = Fixtures.catalogOf(rep);
        AttemptRepository attempts = mock(AttemptRepository.class);
        SubmissionRepository submissions = mock(SubmissionRepository.class);

        ChallengeService service = service(catalog, attempts, submissions, NOW);

        assertThat(service.selectMain(USER)).isEmpty();
    }

    @Test
    void submissionCountFromTheRepositoryDowngradesAMultiTryPassBelowAFirstTryPass() {
        // Same day, same (empty) topics, same outcome - only the submission count differs,
        // so this proves SubmissionRepository#countsForAttempts is actually threaded into
        // ChallengeQuality#derive rather than the derivation defaulting every row alike.
        Exercise cleanFirstTry = minimalChallenge("clean");
        Exercise multiTry = minimalChallenge("multi");
        ExerciseCatalog catalog = Fixtures.catalogOf(cleanFirstTry, multiTry);

        UUID cleanAttempt = UUID.randomUUID();
        UUID multiAttempt = UUID.randomUUID();
        Instant endedAt = NOW.minus(Duration.ofDays(10));

        AttemptRepository attempts = mock(AttemptRepository.class);
        when(attempts.challengeReviews(USER)).thenReturn(List.of(
                new ChallengeAttemptRow(cleanAttempt, "clean", endedAt, true, 0, false, null),
                new ChallengeAttemptRow(multiAttempt, "multi", endedAt, true, 0, false, null)));
        when(attempts.solvedColdExerciseIds(USER)).thenReturn(Set.of());
        when(attempts.firstChallengeAttemptDates(USER)).thenReturn(Map.of());
        SubmissionRepository submissions = mock(SubmissionRepository.class);
        when(submissions.countsForAttempts(List.of(cleanAttempt, multiAttempt)))
                .thenReturn(Map.of(cleanAttempt, 1, multiAttempt, 3));

        ChallengeService service = service(catalog, attempts, submissions, NOW);

        assertThat(service.selectMain(USER)).contains(multiTry);
    }

    @Test
    void anUnderCoveredTopicOutscoresAMoreCoveredOneAllElseEqual() {
        // "arrays" is half-covered (one of two challenges solved cold); "graphs" is
        // entirely uncovered. Both b and c are never attempted, so only the coverage
        // wiring - solvedColdExerciseIds reduced through the catalog's own topics - can
        // explain c winning.
        Exercise a = minimalChallenge("a", "arrays");
        Exercise b = minimalChallenge("b", "arrays");
        Exercise c = minimalChallenge("c", "graphs");
        ExerciseCatalog catalog = Fixtures.catalogOf(a, b, c);

        AttemptRepository attempts = mock(AttemptRepository.class);
        when(attempts.challengeReviews(USER)).thenReturn(List.of());
        when(attempts.solvedColdExerciseIds(USER)).thenReturn(Set.of("a"));
        when(attempts.firstChallengeAttemptDates(USER)).thenReturn(Map.of());
        SubmissionRepository submissions = mock(SubmissionRepository.class);
        when(submissions.countsForAttempts(List.of())).thenReturn(Map.of());

        ChallengeService service = service(catalog, attempts, submissions, NOW);

        assertThat(service.selectMain(USER)).contains(c);
    }

    @Test
    void unrelatedUsersHistoryNeverLeaksIntoAnotherUsersSelection() {
        Exercise challenge = minimalChallenge("only");
        ExerciseCatalog catalog = Fixtures.catalogOf(challenge);
        UUID otherUser = UUID.randomUUID();
        assertThat(otherUser).isNotEqualTo(USER);

        AttemptRepository attempts = mock(AttemptRepository.class);
        // otherUser's history says this challenge was just solved perfectly (too recent to
        // select); USER's own history is empty, so USER must still see it as selectable.
        when(attempts.challengeReviews(otherUser)).thenReturn(List.of(
                new ChallengeAttemptRow(UUID.randomUUID(), "only", NOW, true, 0, false, null)));
        when(attempts.challengeReviews(USER)).thenReturn(List.of());
        when(attempts.solvedColdExerciseIds(USER)).thenReturn(Set.of());
        when(attempts.firstChallengeAttemptDates(USER)).thenReturn(Map.of());
        SubmissionRepository submissions = mock(SubmissionRepository.class);
        when(submissions.countsForAttempts(List.of())).thenReturn(Map.of());

        ChallengeService service = service(catalog, attempts, submissions, NOW);

        assertThat(service.selectMain(USER)).contains(challenge);
    }

    private static ChallengeService service(
            ExerciseCatalog catalog, AttemptRepository attempts, SubmissionRepository submissions, Instant now) {
        CurrentUser currentUser = mock(CurrentUser.class);
        when(currentUser.id()).thenReturn(USER);
        ChallengeSchedulerProperties properties =
                new ChallengeSchedulerProperties(null, null, null, null, null, null, null);
        return new ChallengeService(
                catalog, attempts, submissions, currentUser, properties, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static Exercise minimalChallenge(String id) {
        return minimalChallenge(id, List.of());
    }

    private static Exercise minimalChallenge(String id, String topic) {
        return minimalChallenge(id, List.of(topic));
    }

    private static Exercise minimalChallenge(String id, List<String> topics) {
        return new Exercise(
                id,
                id,
                "Statement",
                "algorithms",
                topics,
                Difficulty.EASY,
                Form.CHALLENGE,
                new Response.FreeText(),
                new Grading.AnswerKey(Fixtures.MAPPER.valueToTree("x"), null),
                List.of());
    }
}
