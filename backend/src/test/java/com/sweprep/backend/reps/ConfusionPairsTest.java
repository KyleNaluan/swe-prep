package com.sweprep.backend.reps;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.TextNode;
import com.sweprep.backend.attempt.SubmissionRepository.FailedResponse;
import com.sweprep.backend.exercise.Comparison;
import com.sweprep.backend.exercise.Difficulty;
import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.Family;
import com.sweprep.backend.exercise.Form;
import com.sweprep.backend.exercise.Grading;
import com.sweprep.backend.exercise.Response;
import com.sweprep.backend.exercise.Stability;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Proves the confusion relation (issue #39) is derived purely from recorded wrong Choice
 * answers and the catalog, with no database. The signal is real: on a wrong answer the
 * distractor a learner picked, when it is the correct label of some other rep, names the
 * pattern they mistook this one for, so the two topics are recorded as confusable. The
 * derivation degrades cleanly - a cold history (every install's current state), content
 * that is not loaded, and a distractor that names no known pattern all contribute
 * nothing rather than failing.
 */
class ConfusionPairsTest {

    // A small catalog: two pattern-identification reps whose correct labels are each
    // other's distractors, so a wrong answer on one that picks the other's label is a
    // genuine confusion signal, plus a third on an unrelated pattern.
    private static final Exercise TWO_POINTER =
            patternRep("two-pointer-id", "two-pointer", "Two pointers");
    private static final Exercise SLIDING_WINDOW =
            patternRep("sliding-window-id", "sliding-window", "Sliding window");
    private static final Exercise BINARY_SEARCH =
            patternRep("binary-search-id", "binary-search", "Binary search");
    private static final List<Exercise> CATALOG =
            List.of(TWO_POINTER, SLIDING_WINDOW, BINARY_SEARCH);

    @Test
    void aColdHistoryYieldsAnEmptyRelation() {
        ConfusionPairs pairs = ConfusionPairs.derive(List.of(), CATALOG);

        assertThat(pairs.isEmpty()).isTrue();
        assertThat(pairs.weight("two-pointer", "sliding-window")).isZero();
    }

    @Test
    void aWrongAnswerPickingAnotherPatternsLabelRecordsTheConfusion() {
        // On the two-pointer rep, the learner picked the sliding-window label: they
        // confused the two patterns.
        ConfusionPairs pairs = ConfusionPairs.derive(
                List.of(new FailedResponse("two-pointer-id", "Sliding window")), CATALOG);

        assertThat(pairs.isEmpty()).isFalse();
        // The relation is symmetric: confusing A for B makes the pair {A, B} confusable.
        assertThat(pairs.weight("two-pointer", "sliding-window")).isEqualTo(1);
        assertThat(pairs.weight("sliding-window", "two-pointer")).isEqualTo(1);
        // A pattern nobody confused these with stays unrelated.
        assertThat(pairs.weight("two-pointer", "binary-search")).isZero();
    }

    @Test
    void repeatedConfusionAccumulatesWeightSoTheStrongerPairIsPreferable() {
        ConfusionPairs pairs = ConfusionPairs.derive(
                List.of(
                        new FailedResponse("two-pointer-id", "Sliding window"),
                        new FailedResponse("sliding-window-id", "Two pointers"),
                        new FailedResponse("two-pointer-id", "Binary search")),
                CATALOG);

        // Two-pointer and sliding-window were confused twice (once each way); two-pointer
        // and binary-search only once, so the first pair is the stronger reason to juxtapose.
        assertThat(pairs.weight("two-pointer", "sliding-window")).isEqualTo(2);
        assertThat(pairs.weight("two-pointer", "binary-search")).isEqualTo(1);
    }

    @Test
    void aWrongAnswerOnUnloadedContentContributesNothing() {
        // The exercise id is not in the catalog (its content is not cloned locally): the
        // correct pattern cannot be resolved, so the wrong answer is ignored, not fatal.
        ConfusionPairs pairs = ConfusionPairs.derive(
                List.of(new FailedResponse("not-in-catalog", "Sliding window")), CATALOG);

        assertThat(pairs.isEmpty()).isTrue();
    }

    @Test
    void aDistractorThatNamesNoKnownPatternContributesNothing() {
        // "Hash set" is a plausible distractor but no rep has it as a correct label, so it
        // resolves to no pattern and records no pair.
        ConfusionPairs pairs = ConfusionPairs.derive(
                List.of(new FailedResponse("two-pointer-id", "Hash set")), CATALOG);

        assertThat(pairs.isEmpty()).isTrue();
    }

    private static Exercise patternRep(String id, String topic, String correctLabel) {
        return new Exercise(
                id,
                id,
                "Which pattern fits?",
                "algorithms",
                List.of(topic),
                Difficulty.EASY,
                Form.REP,
                new Response.Choice(
                        List.of("Two pointers", "Sliding window", "Binary search", "Hash set")),
                new Grading.AnswerKey(TextNode.valueOf(correctLabel), Comparison.exact()),
                List.of(),
                null,
                List.of(Family.CORE),
                Stability.STABLE,
                null,
                null);
    }
}
