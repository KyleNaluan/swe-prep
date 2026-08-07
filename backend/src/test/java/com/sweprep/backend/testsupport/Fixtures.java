package com.sweprep.backend.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sweprep.backend.content.ContentException;
import com.sweprep.backend.exercise.Comparison;
import com.sweprep.backend.exercise.DataType;
import com.sweprep.backend.exercise.Difficulty;
import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.ExerciseCatalog;
import com.sweprep.backend.exercise.Form;
import com.sweprep.backend.exercise.Grading;
import com.sweprep.backend.exercise.Hint;
import com.sweprep.backend.exercise.Response;
import com.sweprep.backend.exercise.Signature;
import com.sweprep.backend.exercise.Signature.Parameter;
import com.sweprep.backend.exercise.TestCase;
import java.util.List;
import java.util.Optional;

/**
 * Synthetic exercises for tests. These are deliberately <em>not</em> real
 * interview problems: no real problem statement, test data or reference solution
 * may live in this repo (issue #4/#14), so the suites prove the mechanism against
 * throwaway demo exercises ("return the two arguments", "the distinct values")
 * rather than against curated content, which is loaded from the private repo at
 * runtime and only smoke-tested when a local clone is present.
 */
public final class Fixtures {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private Fixtures() {}

    /**
     * A code exercise judged order-insensitively: implement {@code pair(a, b)} to
     * return the two arguments in any order. Exercises the test-case grader and the
     * order-insensitive comparison without being a real problem.
     */
    public static Exercise pairInAnyOrder() {
        Signature signature = new Signature(
                "pair",
                List.of(new Parameter("a", DataType.INT), new Parameter("b", DataType.INT)),
                DataType.INT_ARRAY);
        List<TestCase> cases = List.of(
                testCase("[1, 2]", "[1, 2]"),
                testCase("[5, 3]", "[3, 5]"),
                testCase("[7, 7]", "[7, 7]"));
        return new Exercise(
                "pair-in-any-order",
                "Pair In Any Order",
                "Return the two arguments in any order.",
                "algorithms",
                List.of("demo"),
                Difficulty.EASY,
                Form.CHALLENGE,
                new Response.Code(signature),
                new Grading.TestCases(Comparison.orderInsensitiveSequence(), cases),
                PAIR_HINTS);
    }

    /**
     * A three-rung hint ladder for {@link #pairInAnyOrder()}: pattern, approach, then
     * the key insight. Synthetic, like the exercise itself - it proves the ladder is
     * ordered and disclosed one rung at a time, not that any real problem is hinted.
     */
    public static final List<Hint> PAIR_HINTS = List.of(
            new Hint("Pattern", "This is just packing two values into an array."),
            new Hint("Approach", "Allocate an int[] of length two and fill both slots."),
            new Hint("Key insight", "Order does not matter here; return them in either order."));

    /** A correct solution to {@link #pairInAnyOrder()}. */
    public static final String PAIR_SOLUTION =
            """
            class Solution {
                public int[] pair(int a, int b) {
                    return new int[] {a, b};
                }
            }
            """;

    /**
     * A choice exercise judged by a fixed answer key - the demonstration that a
     * grader needs no runner. Options {@code A/B/C}, correct answer {@code B}.
     */
    public static Exercise concept() {
        return new Exercise(
                "concept-demo",
                "Concept Demo",
                "Pick the correct option.",
                "fundamentals",
                List.of("demo"),
                Difficulty.EASY,
                Form.REP,
                new Response.Choice(List.of("A", "B", "C")),
                new Grading.AnswerKey(text("B"), Comparison.exact()),
                List.of());
    }

    /**
     * A code exercise judged by a fixed numeric answer key - "predict the output".
     * Proves the answer-key grader is not string-only: the expected value is a
     * number, matched by magnitude through the exercise's comparison.
     */
    public static Exercise predictNumber() {
        Signature signature = new Signature(
                "value", List.of(new Parameter("n", DataType.INT)), DataType.INT);
        return new Exercise(
                "predict-number",
                "Predict Number",
                "What does this program print?",
                "fundamentals",
                List.of("demo"),
                Difficulty.EASY,
                Form.REP,
                new Response.Code(signature),
                new Grading.AnswerKey(json("42"), Comparison.exact()),
                List.of());
    }

    /**
     * A choice exercise whose options happen to be valid JSON literals
     * ({@code true}/{@code false}) but whose answer key is the string {@code "true"}.
     * Proves a JSON-looking option is not mis-graded against a string answer key.
     */
    public static Exercise booleanLookingChoice() {
        return new Exercise(
                "boolean-choice",
                "Boolean Choice",
                "True or false?",
                "fundamentals",
                List.of("demo"),
                Difficulty.EASY,
                Form.REP,
                new Response.Choice(List.of("true", "false")),
                new Grading.AnswerKey(text("true"), Comparison.exact()),
                List.of());
    }

    /** An in-memory catalog over the given exercises, in argument order. */
    public static ExerciseCatalog catalogOf(Exercise... exercises) {
        List<Exercise> all = List.of(exercises);
        return new ExerciseCatalog() {
            @Override
            public List<Exercise> all() {
                return all;
            }

            @Override
            public Optional<Exercise> byId(String id) {
                return all.stream().filter(e -> e.id().equals(id)).findFirst();
            }
        };
    }

    /** A catalog that fails to load, as a missing or malformed content path would. */
    public static ExerciseCatalog failingCatalog(String message) {
        return new ExerciseCatalog() {
            @Override
            public List<Exercise> all() {
                throw new ContentException(message);
            }

            @Override
            public Optional<Exercise> byId(String id) {
                throw new ContentException(message);
            }
        };
    }

    private static TestCase testCase(String input, String expected) {
        return new TestCase(json(input), json(expected));
    }

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException("Bad fixture JSON: " + raw, e);
        }
    }

    private static JsonNode text(String value) {
        return MAPPER.getNodeFactory().textNode(value);
    }
}
