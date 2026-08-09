package com.sweprep.backend.complexity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sweprep.backend.exercise.Complexity;
import com.sweprep.backend.exercise.ComplexityCheck;
import com.sweprep.backend.exercise.DataType;
import com.sweprep.backend.exercise.Difficulty;
import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.Form;
import com.sweprep.backend.exercise.Grading;
import com.sweprep.backend.exercise.InputGenerator;
import com.sweprep.backend.exercise.Response;
import com.sweprep.backend.exercise.Signature;
import com.sweprep.backend.exercise.Signature.Parameter;
import com.sweprep.backend.language.JavaLanguageAdapter;
import com.sweprep.backend.runner.LocalJavaRunner;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The end-to-end proof of issue #17's core acceptance criteria, run through real
 * compilation and execution exactly as a solver's submission would be (no mocked
 * runner or adapter): a genuinely quadratic solution claiming linear is reliably
 * caught, a genuinely linear one is never wrongly contradicted, an exercise with no
 * input generator skips the check without error, and a submission that cannot be
 * measured at all is reported inconclusive rather than guessed at.
 *
 * <p>The quadratic-catch assertion is exact (the acceptance criterion demands it be
 * "reliably caught"); the linear one is deliberately weaker - it asserts the outcome
 * is never {@code QUADRATIC}/{@code CUBIC}/{@code EXPONENTIAL} rather than pinning an
 * exact bucket. Real wall-clock timing of genuinely cheap code is inherently the
 * hardest case for this technique (see {@code ComplexityProperties}'s Javadoc on the
 * warm-up phase and the JIT/vectorisation effects that motivated it) - a fully
 * reliable exact match there would need either a far larger, slower size range or an
 * unrealistically noise-free machine, neither of which this check depends on for its
 * actual job. What must never happen, and what this asserts, is a false contradiction
 * of a correct claim.
 */
class ScalingMeasurerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // A smaller, faster configuration for the tests that never touch real timing
    // (skip and always-throws) - no reason to pay for four JVM forks there.
    private static final List<Integer> FAST_SIZES = List.of(100, 200);

    private ScalingMeasurer measurer() {
        return new ScalingMeasurer(
                new JavaLanguageAdapter(),
                new LocalJavaRunner(),
                mapper,
                new ComplexityProperties(FAST_SIZES, 0, 1),
                Duration.ofSeconds(10));
    }

    /**
     * The real, shipped default sizes/repetitions ({@link ComplexityProperties}'s
     * defaults) - used by the two tests that actually prove a growth rate is caught,
     * so what is proven here is what production runs, not a specially tuned
     * configuration.
     */
    private ScalingMeasurer defaultsMeasurer() {
        return new ScalingMeasurer(
                new JavaLanguageAdapter(),
                new LocalJavaRunner(),
                mapper,
                new ComplexityProperties(null, null, null),
                Duration.ofSeconds(10));
    }

    /** One INT_ARRAY parameter, scaling with the measured size, returning INT. */
    private Exercise exerciseWithGenerator() {
        Signature signature = new Signature(
                "solve", List.of(new Parameter("nums", DataType.INT_ARRAY)), DataType.INT);
        ComplexityCheck check = new ComplexityCheck(
                Complexity.LINEAR,
                Complexity.CONSTANT,
                new InputGenerator(List.of(new InputGenerator.Argument.ScalingIntArray(0, 1_000_000))));
        return exercise(signature, check);
    }

    private Exercise exercise(Signature signature, ComplexityCheck check) {
        // The grading spec is never exercised here - ScalingMeasurer only reads the
        // response and the complexity check - so one throwaway case is enough to
        // satisfy Exercise's shape.
        com.sweprep.backend.exercise.TestCase throwawayCase;
        try {
            throwawayCase = new com.sweprep.backend.exercise.TestCase(
                    mapper.readTree("[[1]]"), mapper.readTree("0"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return new Exercise(
                "complexity-demo",
                "Complexity Demo",
                "Do something with the array.",
                "algorithms",
                List.of("demo"),
                Difficulty.EASY,
                Form.CHALLENGE,
                new Response.Code(signature),
                new Grading.TestCases(null, List.of(throwawayCase)),
                List.of(),
                null,
                List.of(),
                null,
                null,
                null,
                check);
    }

    @Test
    void aQuadraticSolutionIsReliablyCaughtAsQuadratic() {
        String quadratic =
                """
                class Solution {
                    public int solve(int[] nums) {
                        int count = 0;
                        for (int i = 0; i < nums.length; i++) {
                            for (int j = 0; j < nums.length; j++) {
                                if (nums[i] == nums[j]) {
                                    count++;
                                }
                            }
                        }
                        return count;
                    }
                }
                """;

        MeasurementOutcome outcome = defaultsMeasurer().measure(exerciseWithGenerator(), quadratic);

        assertThat(outcome).isInstanceOfSatisfying(MeasurementOutcome.Conclusive.class,
                conclusive -> assertThat(conclusive.bucket()).isEqualTo(ComplexityBucket.QUADRATIC));
    }

    @Test
    void aLinearSolutionIsNeverWronglyContradicted() {
        // A HashSet-backed distinct-count, not a raw array sum: cheap enough to be
        // genuinely O(n), but not so cheap that the JIT auto-vectorises it into
        // something too fast to time at all (see the class Javadoc).
        String linear =
                """
                import java.util.HashSet;
                class Solution {
                    public int solve(int[] nums) {
                        HashSet<Integer> seen = new HashSet<>();
                        int distinct = 0;
                        for (int x : nums) {
                            if (seen.add(x)) {
                                distinct++;
                            }
                        }
                        return distinct;
                    }
                }
                """;

        MeasurementOutcome outcome = defaultsMeasurer().measure(exerciseWithGenerator(), linear);

        // Inconclusive is an acceptable outcome here (never a false contradiction either);
        // what must never happen is measurement confidently calling genuinely linear code
        // QUADRATIC or worse - the actual safety property this checks.
        assertThat(outcome).satisfiesAnyOf(
                o -> assertThat(o).isInstanceOf(MeasurementOutcome.Inconclusive.class),
                o -> assertThat(o).isInstanceOfSatisfying(MeasurementOutcome.Conclusive.class,
                        conclusive -> assertThat(conclusive.bucket())
                                .isIn(ComplexityBucket.SUBLINEAR, ComplexityBucket.LINEAR)));
    }

    @Test
    void anExerciseWithNoInputGeneratorSkipsTheCheckWithoutError() {
        Signature signature = new Signature(
                "solve", List.of(new Parameter("nums", DataType.INT_ARRAY)), DataType.INT);
        ComplexityCheck targetOnly = new ComplexityCheck(Complexity.LINEAR, Complexity.CONSTANT, null);

        MeasurementOutcome outcome = measurer().measure(exercise(signature, targetOnly), "irrelevant");

        assertThat(outcome).isInstanceOf(MeasurementOutcome.Skipped.class);
    }

    @Test
    void anExerciseWithNoComplexityCheckAtAllSkipsTheCheckWithoutError() {
        Signature signature = new Signature(
                "solve", List.of(new Parameter("nums", DataType.INT_ARRAY)), DataType.INT);

        MeasurementOutcome outcome = measurer().measure(exercise(signature, null), "irrelevant");

        assertThat(outcome).isInstanceOf(MeasurementOutcome.Skipped.class);
    }

    @Test
    void aSubmissionThatAlwaysThrowsIsInconclusiveNeverAsserted() {
        String alwaysThrows =
                """
                class Solution {
                    public int solve(int[] nums) {
                        throw new RuntimeException("boom");
                    }
                }
                """;

        MeasurementOutcome outcome = measurer().measure(exerciseWithGenerator(), alwaysThrows);

        assertThat(outcome).isInstanceOf(MeasurementOutcome.Inconclusive.class);
    }
}
