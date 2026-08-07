package com.sweprep.backend.grader;

import static org.assertj.core.api.Assertions.assertThat;

import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.testsupport.Fixtures;
import org.junit.jupiter.api.Test;

/**
 * Proves the acceptance criterion that a {@code Grader} and a {@code Runner} are
 * separate polymorphic pieces: this grader judges a concept exercise with no
 * runner at all - it is constructed with no runner dependency and never compiles
 * or executes anything.
 */
class AnswerKeyGraderTest {

    private final AnswerKeyGrader grader = new AnswerKeyGrader();
    private final Exercise concept = Fixtures.concept();

    @Test
    void supportsOnlyAnswerKeyGradedExercises() {
        assertThat(grader.supports(concept)).isTrue();
        assertThat(grader.supports(Fixtures.pairInAnyOrder())).isFalse();
    }

    @Test
    void theCorrectChoicePasses() {
        Verdict verdict = grader.grade(concept, "B");

        assertThat(verdict.outcome()).isEqualTo(Verdict.Outcome.PASSED);
        assertThat(verdict.passed()).isEqualTo(1);
        assertThat(verdict.total()).isEqualTo(1);
    }

    @Test
    void aWrongChoiceFails() {
        Verdict verdict = grader.grade(concept, "A");

        assertThat(verdict.outcome()).isEqualTo(Verdict.Outcome.FAILED);
        assertThat(verdict.passed()).isEqualTo(0);
        assertThat(verdict.total()).isEqualTo(1);
    }

    @Test
    void surroundingWhitespaceIsIgnored() {
        assertThat(grader.grade(concept, "  B  ").outcome()).isEqualTo(Verdict.Outcome.PASSED);
    }

    @Test
    void aBlankOrMissingAnswerFails() {
        assertThat(grader.grade(concept, "").outcome()).isEqualTo(Verdict.Outcome.FAILED);
        assertThat(grader.grade(concept, null).outcome()).isEqualTo(Verdict.Outcome.FAILED);
    }
}
