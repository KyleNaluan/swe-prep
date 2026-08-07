package com.sweprep.backend.grader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.testsupport.Fixtures;
import org.junit.jupiter.api.Test;

/**
 * Proves the load-bearing boundary of the self-graded produce-then-reveal format
 * (design revision t3, section 1.1): a self-check is routed like any other grading
 * kind, reveals a model answer with no runner, and - critically - can never be
 * turned into a machine verdict. The boundary lives in the type, not in callers.
 */
class SelfCheckGraderTest {

    private final SelfCheckGrader grader = new SelfCheckGrader();
    private final Exercise explain = Fixtures.explain();

    @Test
    void supportsOnlySelfCheckGradedExercises() {
        assertThat(grader.supports(explain)).isTrue();
        assertThat(grader.supports(Fixtures.concept())).isFalse();
        assertThat(grader.supports(Fixtures.pairInAnyOrder())).isFalse();
    }

    @Test
    void revealsTheModelAnswerWithoutJudging() {
        assertThat(grader.reveal(explain)).isEqualTo("The model answer to compare yourself against.");
    }

    @Test
    void gradeNeverEmitsAVerdict() {
        assertThatThrownBy(() -> grader.grade(explain, "my own explanation"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("never machine-graded")
                .hasMessageContaining("explain-demo");
    }

    @Test
    void revealRejectsAnExerciseItDoesNotSupport() {
        assertThatThrownBy(() -> grader.reveal(Fixtures.concept()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not self-check graded");
    }
}
