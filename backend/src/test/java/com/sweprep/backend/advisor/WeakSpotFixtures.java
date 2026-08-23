package com.sweprep.backend.advisor;

import com.sweprep.backend.exercise.Comparison;
import com.sweprep.backend.exercise.Complexity;
import com.sweprep.backend.exercise.DataType;
import com.sweprep.backend.exercise.Difficulty;
import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.Form;
import com.sweprep.backend.exercise.Grading;
import com.sweprep.backend.exercise.Response;
import com.sweprep.backend.exercise.Signature;
import com.sweprep.backend.exercise.Signature.Parameter;
import com.sweprep.backend.exercise.TestCase;
import com.fasterxml.jackson.databind.node.IntNode;
import java.util.List;

/**
 * Synthetic solutions to the known model weak spots issue #83 names explicitly:
 * amortised analysis, memoised recursion, and hidden Java library costs (string
 * concatenation in a loop, {@code List.contains}, {@code substring} copying). Each
 * pairs its actual complexity with the misreading a model commonly makes on that
 * exact shape, so {@link ComplexityDisagreementTest} can prove the three-way
 * comparison and disagreement prompt behave correctly on precisely the cases this
 * feature exists to surface - never real interview content (issue #4/#14), just
 * enough source to be structurally representative.
 */
final class WeakSpotFixtures {

    private WeakSpotFixtures() {}

    record WeakSpot(
            String name,
            Exercise exercise,
            String submissionSource,
            Complexity actualTime,
            Complexity commonModelMisreading,
            String note) {}

    /**
     * A hand-rolled dynamic array that doubles its capacity on overflow. Amortised
     * over {@code n} pushes this is O(1) per push, i.e. O(n) overall - but summing
     * each individual resize's O(k) copy without amortising reads as O(n^2), the
     * misreading a model can make when it reasons step-by-step instead of amortised.
     */
    static final WeakSpot AMORTISED_DOUBLING = new WeakSpot(
            "amortised doubling array",
            exercise(
                    "amortised-push",
                    "Push n values onto a dynamic array",
                    "Push each of n values onto an array that doubles its capacity when full."),
            """
            class Solution {
                public int[] pushAll(int[] values) {
                    int[] backing = new int[1];
                    int size = 0;
                    for (int v : values) {
                        if (size == backing.length) {
                            int[] grown = new int[backing.length * 2];
                            System.arraycopy(backing, 0, grown, 0, size);
                            backing = grown;
                        }
                        backing[size++] = v;
                    }
                    return backing;
                }
            }
            """,
            Complexity.LINEAR,
            Complexity.QUADRATIC,
            "each resize costs O(k), but resizes happen O(log n) times and the total "
                    + "copying across all of them is O(n) - amortised O(1) per push");

    /**
     * Fibonacci with a memo cache. Structurally it still *looks* like the classic
     * exponential double-recursion; the memo check is the one line that changes
     * everything, and a model that skims past it reads this as exponential.
     */
    static final WeakSpot MEMOISED_RECURSION = new WeakSpot(
            "memoised recursion",
            exercise(
                    "memo-fib",
                    "Nth Fibonacci number",
                    "Return the nth Fibonacci number."),
            """
            import java.util.HashMap;
            import java.util.Map;

            class Solution {
                private final Map<Integer, Long> memo = new HashMap<>();

                public long fib(int n) {
                    if (n <= 1) {
                        return n;
                    }
                    if (memo.containsKey(n)) {
                        return memo.get(n);
                    }
                    long result = fib(n - 1) + fib(n - 2);
                    memo.put(n, result);
                    return result;
                }
            }
            """,
            Complexity.LINEAR,
            Complexity.EXPONENTIAL,
            "each n is computed once and cached; without noticing the memo check this "
                    + "reads as the classic unmemoised double recursion");

    /** {@code +=} on a {@code String} in a loop: each concatenation copies the whole string so far. */
    static final WeakSpot STRING_CONCAT_IN_LOOP = new WeakSpot(
            "string concatenation in a loop",
            exercise(
                    "concat-join",
                    "Join words with a space",
                    "Join a list of words into one space-separated string."),
            """
            import java.util.List;

            class Solution {
                public String join(List<String> words) {
                    String result = "";
                    for (String word : words) {
                        result += word + " ";
                    }
                    return result;
                }
            }
            """,
            Complexity.QUADRATIC,
            Complexity.LINEAR,
            "each += allocates and copies a new String of the whole result so far - "
                    + "n iterations of an O(k) copy is O(n^2), easy to misread as 'just a loop'");

    /** {@code ArrayList.contains} is a linear scan; calling it inside a loop is quadratic. */
    static final WeakSpot LIST_CONTAINS_IN_LOOP = new WeakSpot(
            "List.contains in a loop",
            exercise(
                    "dedupe-list",
                    "Deduplicate a list",
                    "Return the input list with duplicate values removed, preserving order."),
            """
            import java.util.ArrayList;
            import java.util.List;

            class Solution {
                public List<Integer> dedupe(List<Integer> values) {
                    List<Integer> seen = new ArrayList<>();
                    for (int v : values) {
                        if (!seen.contains(v)) {
                            seen.add(v);
                        }
                    }
                    return seen;
                }
            }
            """,
            Complexity.QUADRATIC,
            Complexity.LINEAR,
            "ArrayList.contains is itself an O(n) scan; calling it once per element "
                    + "makes the whole loop O(n^2), invisible if the loop body is read as O(1)");

    /** {@code substring} copies the characters it returns; slicing inside a loop is quadratic. */
    static final WeakSpot SUBSTRING_IN_LOOP = new WeakSpot(
            "substring in a loop",
            exercise(
                    "prefixes",
                    "Every prefix of a string",
                    "Return every prefix of the input string, shortest first."),
            """
            import java.util.ArrayList;
            import java.util.List;

            class Solution {
                public List<String> prefixes(String s) {
                    List<String> result = new ArrayList<>();
                    for (int i = 1; i <= s.length(); i++) {
                        result.add(s.substring(0, i));
                    }
                    return result;
                }
            }
            """,
            Complexity.QUADRATIC,
            Complexity.LINEAR,
            "each substring call copies up to i characters; summed over n prefixes "
                    + "that is O(n^2) of copying, easy to misread as O(n) since it's one call per iteration");

    static final List<WeakSpot> ALL = List.of(
            AMORTISED_DOUBLING, MEMOISED_RECURSION, STRING_CONCAT_IN_LOOP, LIST_CONTAINS_IN_LOOP, SUBSTRING_IN_LOOP);

    private static Exercise exercise(String id, String title, String statement) {
        Signature signature = new Signature(
                "solve", List.of(new Parameter("input", DataType.INT_ARRAY)), DataType.INT);
        return new Exercise(
                id,
                title,
                statement,
                "algorithms",
                List.of("demo"),
                Difficulty.MEDIUM,
                Form.CHALLENGE,
                new Response.Code(signature),
                new Grading.TestCases(Comparison.exact(), List.of(new TestCase(IntNode.valueOf(1), IntNode.valueOf(1)))),
                List.of());
    }
}
