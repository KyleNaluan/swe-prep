package com.sweprep.backend.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sweprep.backend.language.JavaLanguageAdapter;
import com.sweprep.backend.testsupport.Fixtures;
import org.junit.jupiter.api.Test;

/**
 * The free-text rendering boundary (issue #18, design revision t3 T5). A free-text
 * response covers two different items distinguished by their grading: a "predict the
 * output" rep (answer key) renders as a plain text box now, while a self-check item
 * (its produce-then-reveal flow is a later ticket) still refuses to render, so turning
 * on predict-output did not smuggle in the unbuilt self-check view.
 */
class ExerciseViewTest {

    private final JavaLanguageAdapter adapter = new JavaLanguageAdapter();

    @Test
    void aPredictOutputRepRendersAsAFreeTextBox() {
        ExerciseView view = ExerciseView.of(Fixtures.predictOutputRep(), adapter);

        assertThat(view.response().kind()).isEqualTo("freeText");
        assertThat(view.response().options()).isNull();
        assertThat(view.response().stub()).isNull();
    }

    @Test
    void aSelfCheckItemStillRefusesToRender() {
        assertThatThrownBy(() -> ExerciseView.of(Fixtures.explain(), adapter))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("Self-check");
    }
}
