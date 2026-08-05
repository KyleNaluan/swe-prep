package com.sweprep.backend.exercise;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sweprep.backend.exercise.Signature.Parameter;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Supplies the single exercise the tracer-bullet ticket (issue #13) runs
 * end to end. The statement, signature and cases are hardcoded here on purpose:
 * loading real content from the private content repo is the next ticket (#14),
 * and is explicitly out of scope for this one. When that lands, this component
 * is replaced by one that reads the same {@link Exercise} model from content.
 */
@Component
public class ExerciseCatalog {

    private static final String TWO_SUM_STATEMENT =
            """
            Given an array of integers `nums` and an integer `target`, return the \
            indices of the two numbers that add up to `target`.

            Each input has exactly one solution, and you may not use the same \
            element twice. Return the two indices in ascending order.""";

    private final Exercise current;

    public ExerciseCatalog(ObjectMapper mapper) {
        this.current = buildTwoSum(mapper);
    }

    /** The exercise currently served to the editor. */
    public Exercise current() {
        return current;
    }

    private static Exercise buildTwoSum(ObjectMapper mapper) {
        Signature signature = new Signature(
                "twoSum",
                List.of(
                        new Parameter("nums", DataType.INT_ARRAY),
                        new Parameter("target", DataType.INT)),
                DataType.INT_ARRAY);

        // Cases are language-neutral JSON: `input` is the positional argument
        // list, `expected` is the value the return must equal. No Java here.
        List<TestCase> cases = List.of(
                testCase(mapper, "[[2, 7, 11, 15], 9]", "[0, 1]"),
                testCase(mapper, "[[3, 2, 4], 6]", "[1, 2]"),
                testCase(mapper, "[[3, 3], 6]", "[0, 1]"),
                testCase(mapper, "[[-1, -2, -3, -4, -5], -8]", "[2, 4]"));

        return new Exercise(
                "two-sum",
                "Two Sum",
                TWO_SUM_STATEMENT,
                signature,
                cases);
    }

    private static TestCase testCase(ObjectMapper mapper, String input, String expected) {
        try {
            return new TestCase(mapper.readTree(input), mapper.readTree(expected));
        } catch (Exception e) {
            throw new IllegalStateException("Malformed hardcoded test case", e);
        }
    }
}
