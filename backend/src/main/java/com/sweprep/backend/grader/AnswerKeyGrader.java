package com.sweprep.backend.grader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.Grading;
import com.sweprep.backend.exercise.Response;
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
        // A "predict the output" rep is a free-text box graded by exact match after
        // normalisation (issue #18): trivial whitespace differences between what was
        // typed and the expected value are not wrong answers. A choice submission is an
        // exact option string, so it is only stripped - collapsing its internal spaces
        // could make two distinct options collide. The expected side is normalised the
        // same way, so a spaced answer key ("race car") still matches.
        boolean freeText = exercise.response() instanceof Response.FreeText;
        String normalized = freeText ? normalizeFreeText(submission) : submission.strip();
        JsonNode expected = freeText ? normalizeTextNode(key.expected()) : key.expected();
        JsonNode asText = TextNode.valueOf(normalized);
        JsonNode asJson = parse(normalized);
        boolean correct = key.comparison().matches(expected, asText)
                || (asJson != null && key.comparison().matches(expected, asJson));
        return Verdict.of(correct ? 1 : 0, 1);
    }

    /** Strip the ends and collapse every internal run of whitespace to a single space. */
    private static String normalizeFreeText(String value) {
        return value.strip().replaceAll("\\s+", " ");
    }

    /**
     * The answer key with the same free-text normalisation applied when it is a plain
     * string, so a value typed with different spacing still matches. A numeric or
     * structured key is untouched - it is compared by magnitude/shape, not by text.
     */
    private JsonNode normalizeTextNode(JsonNode expected) {
        return expected != null && expected.isTextual()
                ? TextNode.valueOf(normalizeFreeText(expected.asText()))
                : expected;
    }

    /**
     * The submission parsed as JSON, or {@code null} when it is not valid JSON (a
     * plain multiple-choice option such as {@code "O(1) average, because..."}). A
     * numeric or structured answer key ("predict-output") is graded through this
     * parsed form under the exercise's {@link com.sweprep.backend.exercise.Comparison}
     * just like a test case, while an option that happens to look like JSON (e.g.
     * {@code "true"} or {@code "1"}) still matches a string answer key through its
     * raw-text form.
     */
    private JsonNode parse(String submission) {
        try {
            return mapper.readTree(submission);
        } catch (Exception e) {
            return null;
        }
    }
}
