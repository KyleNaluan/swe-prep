package com.sweprep.backend.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.sweprep.backend.exercise.Grading;
import com.sweprep.backend.exercise.Option;
import com.sweprep.backend.exercise.Response;
import java.util.Optional;

/**
 * Classifies a {@link Response.Choice}'s options against a {@link Grading.AnswerKey}
 * using exactly the rule {@code AnswerKeyGrader} applies to a submitted choice: the
 * stripped option text, and its parsed-JSON form when it is valid JSON, each compared
 * under the exercise's {@link com.sweprep.backend.exercise.Comparison}. Keeping the rule
 * in one place means an option is classed correct/distractor exactly when a learner
 * picking it would be graded right/wrong - so the loader's distractor gate (issue #42)
 * and the answer-tell quality check (issue #60) never drift from how the option actually
 * grades. Never compare with Jackson's {@code JsonNode.equals}: it keys on node type and
 * would fail numerically-equal answers (see {@code JsonEquality}).
 */
final class ChoiceKeys {

    private ChoiceKeys() {}

    /** Whether {@code optionText} is the correct answer under {@code key}. */
    static boolean matchesKey(Grading.AnswerKey key, String optionText, ObjectMapper mapper) {
        String stripped = optionText.strip();
        if (key.comparison().matches(key.expected(), TextNode.valueOf(stripped))) {
            return true;
        }
        JsonNode asJson = tryParse(stripped, mapper);
        return asJson != null && key.comparison().matches(key.expected(), asJson);
    }

    /** The single correct option under {@code key}, or empty if the key matches none. */
    static Optional<Option> correctOption(
            Response.Choice choice, Grading.AnswerKey key, ObjectMapper mapper) {
        return choice.options().stream()
                .filter(option -> matchesKey(key, option.text(), mapper))
                .findFirst();
    }

    private static JsonNode tryParse(String value, ObjectMapper mapper) {
        try {
            return mapper.readTree(value);
        } catch (Exception e) {
            return null;
        }
    }
}
