package com.sweprep.backend.exercise;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * The <em>grading spec</em>: what an exercise is judged against, and thus which
 * {@code Grader} handles it. It is deliberately independent of the {@link Response}
 * spec (how the answer is entered): both a code and a choice response can be judged
 * against a fixed answer, and only a {@link TestCases} grading needs code to run.
 *
 * <p>A sealed hierarchy, so a genuinely new way to judge an answer is added as one
 * more permitted record with a matching {@code Grader}, not by reshaping the model.
 */
public sealed interface Grading permits Grading.TestCases, Grading.AnswerKey {

    /**
     * Judged by running the submission against language-neutral {@link TestCase}s
     * and comparing each recorded return value to the expected value under the
     * {@link Comparison} rule. This grading needs a runner and a language adapter.
     */
    record TestCases(Comparison comparison, List<TestCase> cases) implements Grading {

        public TestCases {
            comparison = comparison == null ? Comparison.exact() : comparison;
            cases = List.copyOf(cases);
        }
    }

    /**
     * Judged by comparing the submitted answer directly to a fixed expected value
     * under the {@link Comparison} rule - no code is compiled or run, so a grader
     * for this needs no runner at all (the demonstration issue #14 asks for).
     */
    record AnswerKey(JsonNode expected, Comparison comparison) implements Grading {

        public AnswerKey {
            comparison = comparison == null ? Comparison.exact() : comparison;
        }
    }
}
