package com.sweprep.backend.grader;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.ExerciseCatalog;
import com.sweprep.backend.language.JavaLanguageAdapter;
import com.sweprep.backend.runner.LocalJavaRunner;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The tracer bullet's core proof, exercised without the web layer: a Java
 * submission is compiled and run against the language-neutral Two Sum cases, and
 * every verdict path is distinguished - all pass, some fail, a compile error, and
 * a timeout.
 */
class TestCaseGraderTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Exercise twoSum = new ExerciseCatalog(mapper).current();

    private Grader grader(Duration timeout) {
        return new TestCaseGrader(new JavaLanguageAdapter(), new LocalJavaRunner(), mapper, timeout);
    }

    private static final String CORRECT =
            """
            import java.util.HashMap;
            import java.util.Map;

            class Solution {
                public int[] twoSum(int[] nums, int target) {
                    Map<Integer, Integer> seen = new HashMap<>();
                    for (int i = 0; i < nums.length; i++) {
                        int need = target - nums[i];
                        if (seen.containsKey(need)) {
                            return new int[] {seen.get(need), i};
                        }
                        seen.put(nums[i], i);
                    }
                    return new int[] {-1, -1};
                }
            }
            """;

    @Test
    void correctSubmissionPassesEveryCase() {
        Verdict verdict = grader(Duration.ofSeconds(10)).grade(twoSum, CORRECT);

        assertThat(verdict.outcome()).isEqualTo(Verdict.Outcome.PASSED);
        assertThat(verdict.passed()).isEqualTo(verdict.total());
        assertThat(verdict.total()).isEqualTo(4);
    }

    @Test
    void partiallyCorrectSubmissionCountsThePassingCases() {
        // Only checks adjacent pairs: passes the first three cases, misses the
        // fourth, which needs non-adjacent indices.
        String adjacentOnly =
                """
                class Solution {
                    public int[] twoSum(int[] nums, int target) {
                        for (int i = 0; i + 1 < nums.length; i++) {
                            if (nums[i] + nums[i + 1] == target) {
                                return new int[] {i, i + 1};
                            }
                        }
                        return new int[] {-1, -1};
                    }
                }
                """;

        Verdict verdict = grader(Duration.ofSeconds(10)).grade(twoSum, adjacentOnly);

        assertThat(verdict.outcome()).isEqualTo(Verdict.Outcome.FAILED);
        assertThat(verdict.passed()).isEqualTo(3);
        assertThat(verdict.total()).isEqualTo(4);
    }

    @Test
    void bothValidOrderingsAreAcceptedForAnOrderInsensitiveExercise() {
        // Returns the pair with the later index first - the opposite order to what
        // each case lists. Under exact equality this would fail every case; Two Sum
        // declares an order-insensitive comparison, so it passes them all.
        String reversedOrder =
                """
                import java.util.HashMap;
                import java.util.Map;

                class Solution {
                    public int[] twoSum(int[] nums, int target) {
                        Map<Integer, Integer> seen = new HashMap<>();
                        for (int i = 0; i < nums.length; i++) {
                            int need = target - nums[i];
                            if (seen.containsKey(need)) {
                                return new int[] {i, seen.get(need)};
                            }
                            seen.put(nums[i], i);
                        }
                        return new int[] {-1, -1};
                    }
                }
                """;

        Verdict verdict = grader(Duration.ofSeconds(10)).grade(twoSum, reversedOrder);

        assertThat(verdict.outcome()).isEqualTo(Verdict.Outcome.PASSED);
        assertThat(verdict.passed()).isEqualTo(verdict.total());
    }

    @Test
    void aSolutionThatPrintsPastTheOutputCapIsStillGradedOnItsResults() {
        // Floods stdout well past the runner's 1MB cap before returning the correct
        // answer. The result lives in a dedicated file, off this output channel, so
        // it survives the truncation and the submission grades on its answers.
        String noisyButCorrect =
                """
                import java.util.HashMap;
                import java.util.Map;

                class Solution {
                    public int[] twoSum(int[] nums, int target) {
                        for (int i = 0; i < 200_000; i++) {
                            System.out.println("noise line padding to exceed the output cap " + i);
                        }
                        Map<Integer, Integer> seen = new HashMap<>();
                        for (int i = 0; i < nums.length; i++) {
                            int need = target - nums[i];
                            if (seen.containsKey(need)) {
                                return new int[] {seen.get(need), i};
                            }
                            seen.put(nums[i], i);
                        }
                        return new int[] {-1, -1};
                    }
                }
                """;

        Verdict verdict = grader(Duration.ofSeconds(20)).grade(twoSum, noisyButCorrect);

        assertThat(verdict.outcome()).isEqualTo(Verdict.Outcome.PASSED);
        assertThat(verdict.passed()).isEqualTo(verdict.total());
    }

    @Test
    void compileErrorIsReportedAsSuchNotAsATestFailure() {
        String willNotCompile =
                """
                class Solution {
                    public int[] twoSum(int[] nums, int target) {
                        return notAVariable;
                    }
                }
                """;

        Verdict verdict = grader(Duration.ofSeconds(10)).grade(twoSum, willNotCompile);

        assertThat(verdict.outcome()).isEqualTo(Verdict.Outcome.COMPILE_ERROR);
        assertThat(verdict.detail()).contains("Solution.java");
    }

    @Test
    void infiniteLoopIsCaughtByTheTimeout() {
        String infiniteLoop =
                """
                class Solution {
                    public int[] twoSum(int[] nums, int target) {
                        while (true) {
                            // spin forever
                        }
                    }
                }
                """;

        Verdict verdict = grader(Duration.ofSeconds(2)).grade(twoSum, infiniteLoop);

        assertThat(verdict.outcome()).isEqualTo(Verdict.Outcome.TIMEOUT);
    }
}
