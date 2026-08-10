package com.sweprep.backend.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.sweprep.backend.exercise.Complexity;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the structural time-complexity estimate the complexity rep
 * derives from. Each case is a small, obviously-that-shape snippet - the
 * heuristic only has to be confident enough to propose an answer for a human to
 * verify, never authoritative on its own (see the class javadoc).
 */
class ComplexityHeuristicTest {

    @Test
    void noLoopIsConstant() {
        String source = "class Solution {\n    int run(int n) {\n        return n + 1;\n    }\n}\n";
        assertThat(ComplexityHeuristic.estimate(source, "run")).contains(Complexity.CONSTANT);
    }

    @Test
    void oneLoopIsLinear() {
        String source =
                """
                class Solution {
                    int run(int[] nums) {
                        int total = 0;
                        for (int i = 0; i < nums.length; i++) {
                            total += nums[i];
                        }
                        return total;
                    }
                }
                """;
        assertThat(ComplexityHeuristic.estimate(source, "run")).contains(Complexity.LINEAR);
    }

    @Test
    void twoNestedLoopsIsQuadratic() {
        String source =
                """
                class Solution {
                    int run(int[] nums) {
                        int total = 0;
                        for (int i = 0; i < nums.length; i++) {
                            for (int j = 0; j < nums.length; j++) {
                                total += nums[i] * nums[j];
                            }
                        }
                        return total;
                    }
                }
                """;
        assertThat(ComplexityHeuristic.estimate(source, "run")).contains(Complexity.QUADRATIC);
    }

    @Test
    void threeNestedLoopsIsCubic() {
        String source =
                """
                class Solution {
                    int run(int n) {
                        int total = 0;
                        for (int i = 0; i < n; i++) {
                            for (int j = 0; j < n; j++) {
                                for (int k = 0; k < n; k++) {
                                    total++;
                                }
                            }
                        }
                        return total;
                    }
                }
                """;
        assertThat(ComplexityHeuristic.estimate(source, "run")).contains(Complexity.CUBIC);
    }

    @Test
    void aHalvingSingleLoopIsLogarithmic() {
        String source =
                """
                class Solution {
                    int run(int target, int[] nums) {
                        int lo = 0;
                        int hi = nums.length - 1;
                        while (lo < hi) {
                            int mid = (lo + hi) / 2;
                            if (nums[mid] < target) {
                                lo = mid + 1;
                            } else {
                                hi = mid;
                            }
                        }
                        return lo;
                    }
                }
                """;
        assertThat(ComplexityHeuristic.estimate(source, "run")).contains(Complexity.LOGARITHMIC);
    }

    @Test
    void recursionIsRefusedRatherThanGuessed() {
        String source =
                """
                class Solution {
                    int run(int n) {
                        if (n <= 1) {
                            return n;
                        }
                        return run(n - 1) + run(n - 2);
                    }
                }
                """;
        assertThat(ComplexityHeuristic.estimate(source, "run")).isEmpty();
    }

    @Test
    void moreThanCubicNestingIsRefusedRatherThanGuessed() {
        String source =
                """
                class Solution {
                    int run(int n) {
                        int total = 0;
                        for (int i = 0; i < n; i++) {
                            for (int j = 0; j < n; j++) {
                                for (int k = 0; k < n; k++) {
                                    for (int m = 0; m < n; m++) {
                                        total++;
                                    }
                                }
                            }
                        }
                        return total;
                    }
                }
                """;
        assertThat(ComplexityHeuristic.estimate(source, "run")).isEmpty();
    }

    @Test
    void labelsAreConventionalBigONotation() {
        assertThat(ComplexityHeuristic.label(Complexity.CONSTANT)).isEqualTo("O(1)");
        assertThat(ComplexityHeuristic.label(Complexity.LOGARITHMIC)).isEqualTo("O(log n)");
        assertThat(ComplexityHeuristic.label(Complexity.LINEAR)).isEqualTo("O(n)");
        assertThat(ComplexityHeuristic.label(Complexity.QUADRATIC)).isEqualTo("O(n^2)");
    }

    @Test
    void nearestOthersExcludesTheCorrectClassAndReturnsClosestFirst() {
        List<Complexity> others = ComplexityHeuristic.nearestOthers(Complexity.LINEAR, 3);

        assertThat(others).doesNotContain(Complexity.LINEAR);
        assertThat(others).hasSize(3);
        // LOGARITHMIC and LINEARITHMIC are the ladder's immediate neighbours of LINEAR.
        assertThat(others.subList(0, 2))
                .containsExactlyInAnyOrder(Complexity.LOGARITHMIC, Complexity.LINEARITHMIC);
    }

    @Test
    void misconceptionNamesTheLogarithmicConfusionSpecifically() {
        String misconception = ComplexityHeuristic.misconception(Complexity.LINEAR, Complexity.LOGARITHMIC);
        assertThat(misconception).contains("halved");
    }
}
