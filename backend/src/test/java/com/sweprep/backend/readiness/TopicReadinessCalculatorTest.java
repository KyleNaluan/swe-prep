package com.sweprep.backend.readiness;

import static org.assertj.core.api.Assertions.assertThat;

import com.sweprep.backend.exercise.Comparison;
import com.sweprep.backend.exercise.Difficulty;
import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.Form;
import com.sweprep.backend.exercise.Grading;
import com.sweprep.backend.exercise.Option;
import com.sweprep.backend.exercise.Response;
import com.sweprep.backend.exercise.Stability;
import com.sweprep.backend.learned.LearnedState;
import com.sweprep.backend.testsupport.Fixtures;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The two topic-level readiness axes (issue #22), tested directly with no database: a
 * topic is only ever shaky or stale once it has actually been attempted - an untouched
 * topic is "not covered" instead, a different axis this class never speaks to.
 */
class TopicReadinessCalculatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 10);
    private static final ReadinessProperties DEFAULT = new ReadinessProperties(null, null);

    @Test
    void anAttemptedTopicBelowTheShakyThresholdIsFlagged() {
        Exercise unlearned = rep("rep-1", "graphs");
        Exercise stillUnlearned = rep("rep-2", "graphs");
        List<Exercise> exercises = List.of(unlearned, stillUnlearned);
        Map<String, LearnedState> states = Map.of(); // neither has reached the learned criterion

        List<String> shaky = TopicReadinessCalculator.shakyTopics(
                exercises, states, Set.of("rep-1"), DEFAULT);

        assertThat(shaky).containsExactly("graphs");
    }

    @Test
    void aTopicNeverAttemptedIsNotFlaggedShaky() {
        Exercise untouched = rep("rep-1", "graphs");

        List<String> shaky = TopicReadinessCalculator.shakyTopics(
                List.of(untouched), Map.of(), Set.of(), DEFAULT);

        assertThat(shaky).isEmpty();
    }

    @Test
    void aTopicAboveTheShakyThresholdIsNotFlagged() {
        Exercise learned = rep("rep-1", "arrays");
        Map<String, LearnedState> states = Map.of("rep-1", learnedState());

        List<String> shaky = TopicReadinessCalculator.shakyTopics(
                List.of(learned), states, Set.of("rep-1"), DEFAULT);

        assertThat(shaky).isEmpty();
    }

    @Test
    void onlyRepFormExercisesCountTowardShakiness() {
        Exercise challenge = Fixtures.pairInAnyOrder(); // CHALLENGE form, tagged with its own topics

        List<String> shaky = TopicReadinessCalculator.shakyTopics(
                List.of(challenge), Map.of(), Set.of(challenge.id()), DEFAULT);

        assertThat(shaky).isEmpty();
    }

    @Test
    void aTopicNotTouchedPastTheThresholdIsStale() {
        Exercise stale = rep("rep-1", "dp");
        LocalDate lastTouched = TODAY.minusDays(20);

        List<StaleTopic> result = TopicReadinessCalculator.staleTopics(
                List.of(stale), Set.of("rep-1"), Map.of("rep-1", lastTouched), TODAY, DEFAULT);

        assertThat(result).containsExactly(new StaleTopic("dp", 20));
    }

    @Test
    void aRecentlyTouchedTopicIsNotStale() {
        Exercise fresh = rep("rep-1", "arrays");
        LocalDate lastTouched = TODAY.minusDays(2);

        List<StaleTopic> result = TopicReadinessCalculator.staleTopics(
                List.of(fresh), Set.of("rep-1"), Map.of("rep-1", lastTouched), TODAY, DEFAULT);

        assertThat(result).isEmpty();
    }

    @Test
    void aTopicNeverAttemptedIsNotFlaggedStale() {
        Exercise untouched = rep("rep-1", "graphs");

        List<StaleTopic> result =
                TopicReadinessCalculator.staleTopics(List.of(untouched), Set.of(), Map.of(), TODAY, DEFAULT);

        assertThat(result).isEmpty();
    }

    @Test
    void staleTopicsAreSortedMostStaleFirst() {
        Exercise a = rep("rep-a", "intervals");
        Exercise b = rep("rep-b", "graphs");
        Map<String, LocalDate> lastTouched = Map.of(
                "rep-a", TODAY.minusDays(15),
                "rep-b", TODAY.minusDays(40));

        List<StaleTopic> result = TopicReadinessCalculator.staleTopics(
                List.of(a, b), Set.of("rep-a", "rep-b"), lastTouched, TODAY, DEFAULT);

        assertThat(result).extracting(StaleTopic::topic).containsExactly("graphs", "intervals");
    }

    private static LearnedState learnedState() {
        return new LearnedState(LearnedState.Status.LEARNED, 3, 3, null, 1);
    }

    private static Exercise rep(String id, String topic) {
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
}
