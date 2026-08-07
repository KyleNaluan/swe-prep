package com.sweprep.backend.grader;

import com.fasterxml.jackson.databind.node.TextNode;
import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.Grading;
import org.springframework.stereotype.Component;

/**
 * Grades an exercise whose grading spec is a fixed {@link Grading.AnswerKey}: the
 * submitted answer is compared directly to the expected value under the exercise's
 * {@link com.sweprep.backend.exercise.Comparison} rule.
 *
 * <p>This is the demonstration issue #14 asks for that a {@code Grader} and a
 * {@code Runner} are separate polymorphic pieces: this grader compiles and runs
 * <em>nothing</em>. It has no {@code Runner} dependency at all, so a concept
 * question (a multiple-choice fundamentals rep, say) is graded without an
 * execution seam ever being touched.
 */
@Component
public class AnswerKeyGrader implements Grader {

    @Override
    public boolean supports(Exercise exercise) {
        return exercise.grading() instanceof Grading.AnswerKey;
    }

    @Override
    public Verdict grade(Exercise exercise, String submission) {
        Grading.AnswerKey key = (Grading.AnswerKey) exercise.grading();
        if (submission == null || submission.isBlank()) {
            return Verdict.of(0, 1);
        }
        // The submission is the chosen option as plain text; compare it as a JSON
        // string value so the shared numeric-aware comparison rules still apply.
        TextNode actual = TextNode.valueOf(submission.strip());
        boolean correct = key.comparison().matches(key.expected(), actual);
        return Verdict.of(correct ? 1 : 0, 1);
    }
}
