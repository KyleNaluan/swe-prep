package com.sweprep.backend.grader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private final ObjectMapper mapper;

    public AnswerKeyGrader(ObjectMapper mapper) {
        this.mapper = mapper;
    }

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
        boolean correct = key.comparison().matches(key.expected(), parse(submission.strip()));
        return Verdict.of(correct ? 1 : 0, 1);
    }

    /**
     * A numeric or structured answer key ("predict-output") is graded through the
     * exercise's {@link com.sweprep.backend.exercise.Comparison} just like a test
     * case, so the submission is parsed as JSON. A plain multiple-choice option is
     * not valid JSON, so it falls back to a JSON string value, preserving exact
     * matching against string answer keys.
     */
    private JsonNode parse(String submission) {
        try {
            return mapper.readTree(submission);
        } catch (Exception e) {
            return TextNode.valueOf(submission);
        }
    }
}
