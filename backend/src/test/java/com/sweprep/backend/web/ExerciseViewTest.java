package com.sweprep.backend.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.sweprep.backend.language.JavaLanguageAdapter;
import com.sweprep.backend.testsupport.Fixtures;
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
}
