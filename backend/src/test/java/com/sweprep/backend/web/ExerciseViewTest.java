package com.sweprep.backend.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.sweprep.backend.exercise.Example;
import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.language.JavaLanguageAdapter;
import com.sweprep.backend.testsupport.Fixtures;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The free-text rendering boundary (issues #18, #41). A free-text response covers two
 * different items distinguished by their grading: a "predict the output" rep (answer key)
 * renders as a plain machine-graded text box, while a self-check item (self-check grading)
 * renders as its own produce-then-reveal kind - and, crucially, ships no model answer up
 * front, so the reveal can only come after the learner commits their own text.
 */
class ExerciseViewTest {

    private final JavaLanguageAdapter adapter = new JavaLanguageAdapter();

    @Test
    void aPredictOutputRepRendersAsAFreeTextBox() {
        ExerciseView view = ExerciseView.of(Fixtures.predictOutputRep(), adapter, OptionShuffler.IDENTITY);

        assertThat(view.response().kind()).isEqualTo("freeText");
        assertThat(view.response().options()).isNull();
        assertThat(view.response().stub()).isNull();
    }

    @Test
    void aSelfCheckItemRendersAsSelfCheckWithoutShippingTheModelAnswer() {
        ExerciseView view = ExerciseView.of(Fixtures.explainChallenge(), adapter, OptionShuffler.IDENTITY);

        assertThat(view.response().kind()).isEqualTo("selfCheck");
        assertThat(view.response().options()).isNull();
        assertThat(view.response().stub()).isNull();
        // The model answer must never appear in the up-front view - not in the statement,
        // not anywhere - or produce-then-reveal is defeated before it begins.
        assertThat(view.statement()).doesNotContain("steepest descent");
    }

    // --- The complexity self-report's ordering guarantee (issue #17) ----------------

    @Test
    void aComplexityCheckExerciseFlagsItButNeverShipsTheAuthoredTarget() {
        ExerciseView view = ExerciseView.of(Fixtures.complexityChallenge(), adapter, OptionShuffler.IDENTITY);

        // The editor learns only that a claim will be asked for...
        assertThat(view.hasComplexityCheck()).isTrue();
        // ...never the target itself: this is the real information-ordering guarantee
        // (issue #17) - the target must not be sitting in a response the client already
        // holds while it renders the claim prompt. Serialising the whole view and
        // scanning for the target's own enum name (LINEAR, for this fixture) is a
        // stronger proof than checking a named field, since it also catches a target
        // smuggled in anywhere else - the statement, a hint, wherever.
        assertThat(Fixtures.complexityChallenge().complexityCheck().targetTime())
                .isEqualTo(com.sweprep.backend.exercise.Complexity.LINEAR);
        String serialized = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(view).toString();
        assertThat(serialized).doesNotContain("LINEAR");
    }

    @Test
    void anExerciseWithNoComplexityCheckReportsItAbsent() {
        ExerciseView view = ExerciseView.of(Fixtures.pairInAnyOrder(), adapter, OptionShuffler.IDENTITY);

        assertThat(view.hasComplexityCheck()).isFalse();
    }

    // --- LeetCode-style examples and constraints (issue: swe-examples-feedback) -------

    @Test
    void anExerciseWithExamplesAndConstraintsShipsThemOnTheView() {
        Exercise base = Fixtures.pairInAnyOrder();
        Exercise withExamples = new Exercise(
                base.id(), base.title(), base.statement(), base.domain(), base.topics(),
                base.difficulty(), base.form(), base.response(), base.grading(), base.hints(),
                base.explanation(), base.family(), base.stability(), base.reviewed(),
                base.derivedFrom(), base.complexityCheck(),
                List.of(new Example("nums = [2,7,11,15], target = 9", "[0,1]", "explains it")),
                List.of("2 <= nums.length <= 10^4"));

        ExerciseView view = ExerciseView.of(withExamples, adapter, OptionShuffler.IDENTITY);

        assertThat(view.examples()).hasSize(1);
        assertThat(view.examples().get(0).input()).isEqualTo("nums = [2,7,11,15], target = 9");
        assertThat(view.examples().get(0).output()).isEqualTo("[0,1]");
        assertThat(view.examples().get(0).explanation()).isEqualTo("explains it");
        assertThat(view.constraints()).containsExactly("2 <= nums.length <= 10^4");
    }

    @Test
    void anExerciseWithNoExamplesOrConstraintsShipsEmptyLists() {
        ExerciseView view = ExerciseView.of(Fixtures.pairInAnyOrder(), adapter, OptionShuffler.IDENTITY);

        assertThat(view.examples()).isEmpty();
        assertThat(view.constraints()).isEmpty();
    }
}
