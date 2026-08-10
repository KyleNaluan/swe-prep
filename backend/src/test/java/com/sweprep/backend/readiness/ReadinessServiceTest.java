package com.sweprep.backend.readiness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sweprep.backend.attempt.AttemptRepository;
import com.sweprep.backend.attempt.CurrentUser;
import com.sweprep.backend.exercise.Comparison;
import com.sweprep.backend.exercise.Content;
import com.sweprep.backend.exercise.ContentCatalog;
import com.sweprep.backend.exercise.DataType;
import com.sweprep.backend.exercise.Difficulty;
import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.Family;
import com.sweprep.backend.exercise.Form;
import com.sweprep.backend.exercise.Grading;
import com.sweprep.backend.exercise.Lesson;
import com.sweprep.backend.exercise.Option;
import com.sweprep.backend.exercise.Response;
import com.sweprep.backend.exercise.Signature;
import com.sweprep.backend.exercise.Signature.Parameter;
import com.sweprep.backend.exercise.Stability;
import com.sweprep.backend.learned.LearnedService;
import com.sweprep.backend.learned.LearnedState;
import com.sweprep.backend.testsupport.Fixtures;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Proves the readiness picture's three-way separation (issue #45, design revision t3
 * section 4.4): the objective competence axes come only from {@link LearnedService} and
 * {@link AttemptRepository#solvedColdExerciseIds}, concepts-covered comes only from
 * {@link AttemptRepository#readLessonIds}, and the self-check count is a bare, separate
 * number that a self-check can never inflate the other two with. Also proves the
 * shaky/stale topic axes (issue #22) flow through {@link ReadinessService} correctly -
 * the derivation itself is unit-tested directly in {@link TopicReadinessCalculatorTest}.
 */
class ReadinessServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-10T12:00:00Z"), ZoneOffset.UTC);

    private final UUID user = UUID.randomUUID();

    @Test
    void checksToCriterionCountsOnlyRepFormExercisesReachingTheLearnedCriterion() {
        Exercise learnedRep = repWithId("rep-learned");
        Exercise learningRep = repWithId("rep-learning");
        Exercise challenge = Fixtures.pairInAnyOrder(); // CHALLENGE form, excluded from this axis

        AttemptRepository attempts = attemptsWith(Set.of(), Set.of(), Set.of(), Set.of(), Map.of());
        LearnedService learned = mock(LearnedService.class);
        when(learned.statesForAll(user)).thenReturn(Map.of(
                "rep-learned", learnedState(LearnedState.Status.LEARNED),
                "rep-learning", learnedState(LearnedState.Status.LEARNING)));

        ReadinessService service = service(catalog(learnedRep, learningRep, challenge), learned, attempts);

        ReadinessSummary summary = service.summary(user);

        assertThat(summary.checksToCriterion()).isEqualTo(new Progress(1, 2));
    }

    @Test
    void solvedColdCountsOnlyChallengeFormExercisesSolvedWithNoHelp() {
        Exercise solvedCold = Fixtures.pairInAnyOrder();
        Exercise solvedWithHelp = challengeWithId("solved-with-help");
        Exercise rep = repWithId("rep-untouched"); // REP form, excluded from this axis

        AttemptRepository attempts =
                attemptsWith(Set.of(solvedCold.id()), Set.of(), Set.of(), Set.of(), Map.of());
        LearnedService learned = mock(LearnedService.class);
        when(learned.statesForAll(user)).thenReturn(Map.of());

        ReadinessService service = service(catalog(solvedCold, solvedWithHelp, rep), learned, attempts);

        ReadinessSummary summary = service.summary(user);

        // Two CHALLENGE-form exercises exist; only the one with no help taken counts.
        assertThat(summary.solvedCold()).isEqualTo(new Progress(1, 2));
    }

    @Test
    void conceptsCoveredCountsLessonsReadAndIsIndependentOfExerciseCompetence() {
        Lesson readLesson = lesson("lesson-read");
        Lesson unreadLesson = lesson("lesson-unread");

        AttemptRepository attempts =
                attemptsWith(Set.of(), Set.of("lesson-read"), Set.of(), Set.of(), Map.of());
        LearnedService learned = mock(LearnedService.class);
        when(learned.statesForAll(user)).thenReturn(Map.of());

        ReadinessService service = service(catalog(readLesson, unreadLesson), learned, attempts);

        ReadinessSummary summary = service.summary(user);

        assertThat(summary.conceptsCovered()).isEqualTo(new Progress(1, 2));
    }

    @Test
    void selfCheckExplainedCountIsSeparateAndNeverFoldedIntoTheObjectiveAxes() {
        Exercise explainItem = repWithId("rep-explained"); // never learned, never solved cold
        AttemptRepository attempts =
                attemptsWith(Set.of(), Set.of(), Set.of("rep-explained"), Set.of(), Map.of());
        LearnedService learned = mock(LearnedService.class);
        when(learned.statesForAll(user)).thenReturn(Map.of());

        ReadinessService service = service(catalog(explainItem), learned, attempts);

        ReadinessSummary summary = service.summary(user);

        // Explained once, but that alone never counts as a clean pass toward checks-to-criterion.
        assertThat(summary.selfCheckExplainedCount()).isEqualTo(1);
        assertThat(summary.checksToCriterion()).isEqualTo(new Progress(0, 1));
    }

    @Test
    void theFamilyBreakdownScopesEachAxisToThatFamilysContentOnly() {
        Exercise backendLearned = repWithFamily("rep-backend", Family.BACKEND);
        Exercise frontendLearning = repWithFamily("rep-frontend", Family.FRONTEND);

        AttemptRepository attempts = attemptsWith(Set.of(), Set.of(), Set.of(), Set.of(), Map.of());
        LearnedService learned = mock(LearnedService.class);
        when(learned.statesForAll(user)).thenReturn(Map.of(
                "rep-backend", learnedState(LearnedState.Status.LEARNED),
                "rep-frontend", learnedState(LearnedState.Status.LEARNING)));

        ReadinessService service = service(catalog(backendLearned, frontendLearning), learned, attempts);

        ReadinessSummary summary = service.summary(user);

        FamilyReadiness backend = familyLine(summary, Family.BACKEND);
        FamilyReadiness frontend = familyLine(summary, Family.FRONTEND);
        FamilyReadiness aiml = familyLine(summary, Family.AIML);

        assertThat(backend.checksToCriterion()).isEqualTo(new Progress(1, 1));
        assertThat(frontend.checksToCriterion()).isEqualTo(new Progress(0, 1));
        // A family with nothing tagged reports 0/0 rather than being omitted.
        assertThat(aiml.checksToCriterion()).isEqualTo(new Progress(0, 0));
    }

    @Test
    void shakyTopicsFlagsAnAttemptedButUnreliableTopicAndSkipsAnUntouchedOne() {
        Exercise shakyRep = repWithTopic("rep-shaky", "graphs");
        Exercise coveredRep = repWithTopic("rep-covered", "arrays");

        AttemptRepository attempts = attemptsWith(
                Set.of(), Set.of(), Set.of(), Set.of("rep-shaky", "rep-covered"), Map.of());
        LearnedService learned = mock(LearnedService.class);
        when(learned.statesForAll(user)).thenReturn(Map.of(
                "rep-shaky", learnedState(LearnedState.Status.LEARNING),
                "rep-covered", learnedState(LearnedState.Status.LEARNED)));

        ReadinessService service = service(catalog(shakyRep, coveredRep), learned, attempts);

        ReadinessSummary summary = service.summary(user);

        assertThat(summary.shakyTopics()).containsExactly("graphs");
    }

    @Test
    void staleTopicsFlagsATopicNotTouchedRecentlyAndSkipsAnUntouchedOne() {
        Exercise staleRep = repWithTopic("rep-stale", "dp");
        Exercise freshRep = repWithTopic("rep-fresh", "arrays");

        Instant longAgo = CLOCK.instant().minus(30, java.time.temporal.ChronoUnit.DAYS);
        Instant recently = CLOCK.instant().minus(1, java.time.temporal.ChronoUnit.DAYS);
        AttemptRepository attempts = attemptsWith(
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of("rep-stale", "rep-fresh"),
                Map.of("rep-stale", longAgo, "rep-fresh", recently));
        LearnedService learned = mock(LearnedService.class);
        when(learned.statesForAll(user)).thenReturn(Map.of());

        ReadinessService service = service(catalog(staleRep, freshRep), learned, attempts);

        ReadinessSummary summary = service.summary(user);

        assertThat(summary.staleTopics()).extracting(StaleTopic::topic).containsExactly("dp");
        assertThat(summary.staleTopics().get(0).daysSinceTouched()).isEqualTo(30);
    }

    private static FamilyReadiness familyLine(ReadinessSummary summary, Family family) {
        return summary.families().stream()
                .filter(f -> f.family() == family)
                .findFirst()
                .orElseThrow();
    }

    private static LearnedState learnedState(LearnedState.Status status) {
        return new LearnedState(status, status == LearnedState.Status.LEARNED ? 3 : 1, 3, null, 1);
    }

    private CurrentUser currentUser() {
        CurrentUser currentUser = mock(CurrentUser.class);
        when(currentUser.id()).thenReturn(user);
        return currentUser;
    }

    private ReadinessService service(ContentCatalog catalog, LearnedService learned, AttemptRepository attempts) {
        return new ReadinessService(catalog, learned, attempts, currentUser(), new ReadinessProperties(null, null), CLOCK);
    }

    private static AttemptRepository attemptsWith(
            Set<String> solvedCold,
            Set<String> readLessons,
            Set<String> explained,
            Set<String> attemptedExerciseIds,
            Map<String, Instant> lastAttemptDates) {
        AttemptRepository attempts = mock(AttemptRepository.class);
        when(attempts.solvedColdExerciseIds(org.mockito.ArgumentMatchers.any())).thenReturn(solvedCold);
        when(attempts.readLessonIds(org.mockito.ArgumentMatchers.any())).thenReturn(readLessons);
        when(attempts.explainedExerciseIds(org.mockito.ArgumentMatchers.any())).thenReturn(explained);
        when(attempts.attemptedExerciseIds(org.mockito.ArgumentMatchers.any())).thenReturn(attemptedExerciseIds);
        when(attempts.lastAttemptDates(org.mockito.ArgumentMatchers.any())).thenReturn(lastAttemptDates);
        return attempts;
    }

    private static ContentCatalog catalog(Content... content) {
        List<Content> all = List.of(content);
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

    private static Exercise repWithId(String id) {
        return repWithFamily(id, null);
    }

    private static Exercise repWithTopic(String id, String topic) {
        return new Exercise(
                id,
                id,
                "A rep.",
                "fundamentals",
                List.of(topic),
                Difficulty.EASY,
                Form.REP,
                new Response.Choice(List.of(Option.correct("A"))),
                new Grading.AnswerKey(Fixtures.MAPPER.getNodeFactory().textNode("A"), Comparison.exact()),
                List.of(),
                null,
                List.of(),
                Stability.STABLE,
                null,
                null);
    }

    private static Exercise repWithFamily(String id, Family family) {
        return new Exercise(
                id,
                id,
                "A rep.",
                "fundamentals",
                List.of("demo"),
                Difficulty.EASY,
                Form.REP,
                new Response.Choice(List.of(Option.correct("A"))),
                new Grading.AnswerKey(Fixtures.MAPPER.getNodeFactory().textNode("A"), Comparison.exact()),
                List.of(),
                null,
                family == null ? List.of() : List.of(family),
                Stability.STABLE,
                null,
                null);
    }

    private static Exercise challengeWithId(String id) {
        Signature signature = new Signature(
                "identity", List.of(new Parameter("a", DataType.INT)), DataType.INT);
        return new Exercise(
                id,
                id,
                "A challenge.",
                "algorithms",
                List.of("demo"),
                Difficulty.EASY,
                Form.CHALLENGE,
                new Response.Code(signature),
                new Grading.TestCases(Comparison.exact(), List.of()),
                List.of());
    }

    private static Lesson lesson(String id) {
        return new Lesson(id, id, "Taught content.", "fundamentals", List.of("demo"), Difficulty.EASY, List.of());
    }
}
