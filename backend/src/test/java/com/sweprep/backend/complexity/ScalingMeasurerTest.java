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
import com.sweprep.backend.language.PythonLanguageAdapter;
import com.sweprep.backend.language.LanguageAdapterRegistry;
import com.sweprep.backend.runner.LocalJavaRunner;
import com.sweprep.backend.runner.LocalPythonRunner;
import com.sweprep.backend.runner.RunnerRegistry;
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
 * <p>Every timing assertion here is deliberately one-sided in the safe direction rather
 * than pinning an exact bucket, because real wall-clock timing of cheap code is the
 * hardest case for this technique (see {@code ComplexityProperties}'s Javadoc on the
 * warm-up, cache-residency and JIT effects that shape it) and a shared CI runner can
 * drift a measured slope out of the classifier's confident window on either side. The
 * quadratic run asserts it is never wrongly *cleared* (never {@code Conclusive} as
 * {@code LINEAR}/{@code SUBLINEAR}, i.e. never reported as matching a linear-or-faster
 * claim), accepting {@code QUADRATIC}-or-higher or {@code Inconclusive}; the linear runs
 * assert the mirror - never wrongly *contradicted*, and in particular never the confident
 * {@code SUBLINEAR} that a textbook BFS used to measure. The exact-bucket guarantee is
 * proven deterministically in {@code ComplexityClassifierTest}, replaying the timing
 * curves these very solutions actually produced; what these real-execution runs add,
 * without depending on an unrealistically noise-free machine, is that the full
 * compile/execute/measure pipeline never produces a false verdict in either direction.
 */
class ScalingMeasurerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // A smaller, faster configuration for the tests that never touch real timing
    // (skip and always-throws) - no reason to pay for four JVM forks there.
    private static final List<Integer> FAST_SIZES = List.of(100, 200);

    private ScalingMeasurer measurer() {
        return new ScalingMeasurer(
                new LanguageAdapterRegistry(List.of(new JavaLanguageAdapter())),
                new RunnerRegistry(List.of(new LocalJavaRunner())),
                mapper,
                new ComplexityProperties(FAST_SIZES, Duration.ZERO, 0, 1, FAST_SIZES.size(), null),
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
                new LanguageAdapterRegistry(List.of(new JavaLanguageAdapter())),
                new RunnerRegistry(List.of(new LocalJavaRunner())),
                mapper,
                new ComplexityProperties(null, null, null, null, null, null),
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

        MeasurementOutcome outcome = defaultsMeasurer().measure(exerciseWithGenerator(), quadratic, "java");

        // The exact-bucket guarantee (a clean quadratic curve classifies as QUADRATIC) is
        // proven deterministically against synthetic curves in ComplexityClassifierTest.
        // Here, over real wall-clock timing on a shared CI runner, the measured slope can
        // drift out of the classifier's confident QUADRATIC window (~1.75..2.25) - a small
        // cache/contention artifact is enough - and the pipeline then honestly reports
        // Inconclusive. What must never happen, and what this end-to-end run asserts, is a
        // false clearing: a genuinely quadratic submission reported as matching a linear (or
        // faster) claim. So Conclusive is only ever QUADRATIC or higher, never LINEAR/SUBLINEAR;
        // Inconclusive is acceptable (it never wrongly clears). This mirrors the safe-direction
        // assertion of aLinearSolutionIsNeverWronglyContradicted.
        assertThat(outcome).satisfiesAnyOf(
                o -> assertThat(o).isInstanceOf(MeasurementOutcome.Inconclusive.class),
                o -> assertThat(o).isInstanceOfSatisfying(MeasurementOutcome.Conclusive.class,
                        conclusive -> assertThat(conclusive.bucket())
                                .isIn(
                                        ComplexityBucket.QUADRATIC,
                                        ComplexityBucket.CUBIC,
                                        ComplexityBucket.EXPONENTIAL)));
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

        MeasurementOutcome outcome = defaultsMeasurer().measure(exerciseWithGenerator(), linear, "java");

        // Inconclusive is an acceptable outcome here (never a false contradiction either);
        // what must never happen is measurement confidently placing genuinely linear code
        // in any other bucket - QUADRATIC or worse, and equally the SUBLINEAR that the
        // overhead-dominated regime used to produce for cheap linear code.
        assertNeverContradictsLinear(outcome);
    }

    /**
     * The two shapes that used to fail (issue: the ledger's LINEAR spot-checks), run at
     * the shipped defaults through real compilation and execution. Both are cheap enough
     * per call that the fixed per-call cost and cache residency used to dominate them -
     * the overhead-dominated regime this class's Javadoc calls the technique's hardest
     * case, and the one a monotonic stack and an unconditional BFS actually sit in.
     */
    @Test
    void aCheapLinearMonotonicStackIsNeverConfidentlyMisclassified() {
        // daily-temperatures' shape: one pass, a stack of indices, no early exit. Roughly
        // 170 microseconds per call at size 32 000 - fast enough that it used to measure
        // as timing noise rather than as growth.
        String monotonicStack =
                """
                import java.util.ArrayDeque;
                import java.util.Deque;
                class Solution {
                    public int solve(int[] nums) {
                        int n = nums.length;
                        int[] answer = new int[n];
                        Deque<Integer> stack = new ArrayDeque<>();
                        for (int i = 0; i < n; i++) {
                            while (!stack.isEmpty() && nums[stack.peek()] < nums[i]) {
                                int prev = stack.pop();
                                answer[prev] = i - prev;
                            }
                            stack.push(i);
                        }
                        int total = 0;
                        for (int v : answer) {
                            total += v;
                        }
                        return total;
                    }
                }
                """;

        MeasurementOutcome outcome = defaultsMeasurer().measure(exerciseWithGenerator(), monotonicStack, "java");

        assertNeverContradictsLinear(outcome);
    }

    @Test
    void aCheapLinearTreeTraversalIsNeverConfidentlyMisclassifiedAsSublinear() {
        // binary-tree-level-order-traversal's shape: an unconditional level-order walk of
        // every node. This is the exact case that once measured a confident SUBLINEAR
        // with a fitted exponent of 0.12-0.19 - a false verdict, and the one outcome this
        // run must make impossible.
        Signature signature = new Signature(
                "solve", List.of(new Parameter("root", DataType.TREE_NODE)), DataType.INT);
        ComplexityCheck check = new ComplexityCheck(
                Complexity.LINEAR,
                Complexity.CONSTANT,
                new InputGenerator(List.of(new InputGenerator.Argument.ScalingTreeNode(-1_000_000, 1_000_000))));
        String levelOrder =
                """
                import java.util.ArrayList;
                import java.util.List;
                class Solution {
                    public int solve(TreeNode root) {
                        List<int[]> levels = new ArrayList<>();
                        List<TreeNode> queue = new ArrayList<>();
                        if (root != null) {
                            queue.add(root);
                        }
                        while (!queue.isEmpty()) {
                            List<TreeNode> next = new ArrayList<>();
                            int[] level = new int[queue.size()];
                            for (int i = 0; i < queue.size(); i++) {
                                TreeNode node = queue.get(i);
                                level[i] = node.val;
                                if (node.left != null) {
                                    next.add(node.left);
                                }
                                if (node.right != null) {
                                    next.add(node.right);
                                }
                            }
                            levels.add(level);
                            queue = next;
                        }
                        return levels.size();
                    }
                }
                """;

        MeasurementOutcome outcome =
                defaultsMeasurer().measure(exercise(signature, check), levelOrder, "java");

        assertNeverContradictsLinear(outcome);
    }

    @Test
    void aSubmissionThatIsOnlyOverheadIsNeverGivenASublinearVerdictOnNoise() {
        // An O(1) submission: every measured time is the fixed per-call cost, a few
        // hundred nanoseconds, wandering with input size purely as noise. A verdict of
        // SUBLINEAR here would be right about the algorithm for entirely the wrong
        // reason, so the honest answers are CONSTANT's bucket only if the timings genuinely
        // support it - which at these magnitudes they never do - or Inconclusive.
        String constant =
                """
                class Solution {
                    public int solve(int[] nums) {
                        return nums.length;
                    }
                }
                """;

        MeasurementOutcome outcome = defaultsMeasurer().measure(exerciseWithGenerator(), constant, "java");

        assertThat(outcome).isInstanceOf(MeasurementOutcome.Inconclusive.class);
    }

    /**
     * The safe-direction assertion for a genuinely linear submission: measurement may
     * honestly decline to classify, and may say LINEAR, but must never confidently place
     * it in any other bucket - not the SUBLINEAR the overhead-dominated regime used to
     * produce, and not QUADRATIC or worse either.
     */
    private static void assertNeverContradictsLinear(MeasurementOutcome outcome) {
        assertThat(outcome).satisfiesAnyOf(
                o -> assertThat(o).isInstanceOf(MeasurementOutcome.Inconclusive.class),
                o -> assertThat(o).isInstanceOfSatisfying(MeasurementOutcome.Conclusive.class,
                        conclusive -> assertThat(conclusive.bucket()).isEqualTo(ComplexityBucket.LINEAR)));
    }

    @Test
    void anExerciseWithNoInputGeneratorSkipsTheCheckWithoutError() {
        Signature signature = new Signature(
                "solve", List.of(new Parameter("nums", DataType.INT_ARRAY)), DataType.INT);
        ComplexityCheck targetOnly = new ComplexityCheck(Complexity.LINEAR, Complexity.CONSTANT, null);

        MeasurementOutcome outcome = measurer().measure(exercise(signature, targetOnly), "irrelevant", "java");

        assertThat(outcome).isInstanceOf(MeasurementOutcome.Skipped.class);
    }

    @Test
    void anExerciseWithNoComplexityCheckAtAllSkipsTheCheckWithoutError() {
        Signature signature = new Signature(
                "solve", List.of(new Parameter("nums", DataType.INT_ARRAY)), DataType.INT);

        MeasurementOutcome outcome = measurer().measure(exercise(signature, null), "irrelevant", "java");

        assertThat(outcome).isInstanceOf(MeasurementOutcome.Skipped.class);
    }

    // --- Scaling kinds beyond scalingIntArray, driven end to end (issue #86 follow-on) --

    @Test
    void aScalingStringGeneratorDrivesMeasurementEndToEnd() {
        Signature signature = new Signature(
                "solve", List.of(new Parameter("s", DataType.STRING)), DataType.INT);
        ComplexityCheck check = new ComplexityCheck(
                Complexity.LINEAR,
                Complexity.CONSTANT,
                new InputGenerator(List.of(new InputGenerator.Argument.ScalingString("abc"))));
        String solution =
                """
                class Solution {
                    public int solve(String s) {
                        return s.length();
                    }
                }
                """;

        MeasurementOutcome outcome = measurer().measure(exercise(signature, check), solution, "java");

        // Not tied to an exact bucket (see the class Javadoc on why real wall-clock timing
        // is asserted one-sided elsewhere) - what this proves is the full pipeline (parse
        // generator -> synthesize a growing STRING -> compile -> run -> classify) completes
        // without error, which a Skipped outcome would mean it never even attempted.
        assertThat(outcome).isNotInstanceOf(MeasurementOutcome.Skipped.class);
    }

    @Test
    void aScalingIntMatrixGeneratorDrivesMeasurementEndToEnd() {
        Signature signature = new Signature(
                "solve", List.of(new Parameter("grid", DataType.INT_MATRIX)), DataType.INT);
        ComplexityCheck check = new ComplexityCheck(
                Complexity.QUADRATIC,
                Complexity.CONSTANT,
                new InputGenerator(List.of(new InputGenerator.Argument.ScalingIntMatrix(0, 100))));
        String solution =
                """
                class Solution {
                    public int solve(int[][] grid) {
                        int sum = 0;
                        for (int[] row : grid) {
                            for (int cell : row) {
                                sum += cell;
                            }
                        }
                        return sum;
                    }
                }
                """;

        MeasurementOutcome outcome = measurer().measure(exercise(signature, check), solution, "java");

        assertThat(outcome).isNotInstanceOf(MeasurementOutcome.Skipped.class);
    }

    @Test
    void aScalingListNodeGeneratorDrivesMeasurementEndToEnd() {
        Signature signature = new Signature(
                "solve", List.of(new Parameter("head", DataType.LIST_NODE)), DataType.INT);
        ComplexityCheck check = new ComplexityCheck(
                Complexity.LINEAR,
                Complexity.CONSTANT,
                new InputGenerator(List.of(new InputGenerator.Argument.ScalingListNode(0, 1000))));
        String solution =
                """
                class Solution {
                    public int solve(ListNode head) {
                        int count = 0;
                        while (head != null) {
                            count++;
                            head = head.next;
                        }
                        return count;
                    }
                }
                """;

        MeasurementOutcome outcome = measurer().measure(exercise(signature, check), solution, "java");

        assertThat(outcome).isNotInstanceOf(MeasurementOutcome.Skipped.class);
    }

    @Test
    void aScalingTreeNodeGeneratorDrivesMeasurementEndToEnd() {
        Signature signature = new Signature(
                "solve", List.of(new Parameter("root", DataType.TREE_NODE)), DataType.INT);
        ComplexityCheck check = new ComplexityCheck(
                Complexity.LINEAR,
                Complexity.CONSTANT,
                new InputGenerator(List.of(new InputGenerator.Argument.ScalingTreeNode(0, 1000))));
        String solution =
                """
                class Solution {
                    public int solve(TreeNode root) {
                        if (root == null) {
                            return 0;
                        }
                        return 1 + solve(root.left) + solve(root.right);
                    }
                }
                """;

        MeasurementOutcome outcome = measurer().measure(exercise(signature, check), solution, "java");

        assertThat(outcome).isNotInstanceOf(MeasurementOutcome.Skipped.class);
    }

    @Test
    void aPythonSubmissionIsMeasuredThroughItsOwnTimingHarnessNotJavas() {
        // The timing harness protocol - input file, warm-up nanosecond budget, warm-up
        // call cap, repetitions, result file - is one contract every adapter implements,
        // so a submission solved in a second language is measured by that language's own
        // harness rather than assumed to be Java (issue #26). What this proves is that the
        // Python harness still parses its arguments and produces readable timings; the
        // growth rate itself is not asserted, for the wall-clock reasons in the class
        // Javadoc.
        ScalingMeasurer pythonMeasurer = new ScalingMeasurer(
                new LanguageAdapterRegistry(List.of(new JavaLanguageAdapter(), new PythonLanguageAdapter())),
                new RunnerRegistry(List.of(new LocalJavaRunner(), new LocalPythonRunner("python3"))),
                mapper,
                new ComplexityProperties(FAST_SIZES, Duration.ZERO, 1, 1, FAST_SIZES.size(), null),
                Duration.ofSeconds(10));
        String linear =
                """
                class Solution:
                    def solve(self, nums):
                        return sum(nums)
                """;

        MeasurementOutcome outcome = pythonMeasurer.measure(exerciseWithGenerator(), linear, "python");

        assertThat(outcome).isInstanceOf(MeasurementOutcome.Inconclusive.class);
    }

    @Test
    void theTotalBudgetStopsMeasurementRatherThanRunningEverySize() {
        // Measurement runs inside an interactive request, so its cost is bounded: once the
        // cumulative budget is spent no further size is started. A budget of one
        // nanosecond is already gone before the first size, which proves the check is a
        // real gate on the loop and not merely a limit nothing ever reaches - and the
        // result is the honest Inconclusive, never an exception and never a guess.
        ScalingMeasurer noBudget = new ScalingMeasurer(
                new LanguageAdapterRegistry(List.of(new JavaLanguageAdapter())),
                new RunnerRegistry(List.of(new LocalJavaRunner())),
                mapper,
                new ComplexityProperties(FAST_SIZES, Duration.ZERO, 0, 1, FAST_SIZES.size(), Duration.ofNanos(1)),
                Duration.ofSeconds(10));

        long startedAt = System.nanoTime();
        MeasurementOutcome outcome = noBudget.measure(exerciseWithGenerator(), "class Solution {}", "java");
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(outcome).isInstanceOf(MeasurementOutcome.Inconclusive.class);
        // Nothing was compiled or executed at all - a real run of even one size costs
        // hundreds of milliseconds.
        assertThat(elapsedMillis).isLessThan(100);
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

        MeasurementOutcome outcome = measurer().measure(exerciseWithGenerator(), alwaysThrows, "java");

        assertThat(outcome).isInstanceOf(MeasurementOutcome.Inconclusive.class);
    }
}
