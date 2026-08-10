package com.sweprep.backend.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.sweprep.backend.authoring.MutationCatalog.Category;
import com.sweprep.backend.authoring.MutationCatalog.MutationCandidate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure mutation catalog (no compilation, no execution - see
 * {@code RepDeriverTest} for the empirical "does this mutation actually break
 * the solution" proof). Covers each operator's shape and the condition-line gate
 * that keeps the relational/equality/logical operators away from Java generics.
 */
class MutationCatalogTest {

    @Test
    void relationalBoundaryFlipsOnlyInsideAConditionLine() {
        String source =
                """
                class Solution {
                    Map<Integer, Integer> seen = new HashMap<>();
                    int run(int lo, int hi) {
                        while (lo < hi) {
                            lo++;
                        }
                        return lo;
                    }
                }
                """;
        List<MutationCandidate> candidates = MutationCatalog.candidates(source);

        assertThat(candidates)
                .as("the generic type declaration's '<'/'>' must never be touched")
                .noneMatch(c -> c.originalLine().contains("Map<Integer"));
        assertThat(candidates)
                .anySatisfy(c -> {
                    assertThat(c.category()).isEqualTo(Category.RELATIONAL_BOUNDARY);
                    assertThat(c.originalLine()).contains("lo < hi");
                    assertThat(c.mutatedLine()).contains("lo <= hi");
                });
    }

    @Test
    void incrementFlipAppliesOutsideConditionLines() {
        String source =
                """
                class Solution {
                    int run(int n) {
                        int i = 0;
                        i++;
                        return i;
                    }
                }
                """;
        List<MutationCandidate> candidates = MutationCatalog.candidates(source);

        assertThat(candidates)
                .anySatisfy(c -> {
                    assertThat(c.category()).isEqualTo(Category.INCREMENT_FLIP);
                    assertThat(c.mutatedLine()).contains("i--");
                });
    }

    @Test
    void arithmeticOffsetFlipsPlusOneAndMinusOne() {
        String source =
                """
                class Solution {
                    int run(int mid) {
                        return mid + 1;
                    }
                }
                """;
        List<MutationCandidate> candidates = MutationCatalog.candidates(source);

        assertThat(candidates)
                .anySatisfy(c -> {
                    assertThat(c.category()).isEqualTo(Category.ARITHMETIC_OFFSET);
                    assertThat(c.mutatedLine()).contains("mid - 1");
                });
    }

    @Test
    void literalBumpIncrementsTheFirstIntegerLiteral() {
        String source =
                """
                class Solution {
                    int run() {
                        int seed = 0;
                        return seed;
                    }
                }
                """;
        List<MutationCandidate> candidates = MutationCatalog.candidates(source);

        assertThat(candidates)
                .anySatisfy(c -> {
                    assertThat(c.category()).isEqualTo(Category.LITERAL_BUMP);
                    assertThat(c.originalLine()).contains("= 0");
                    assertThat(c.mutatedLine()).contains("= 1");
                });
    }

    @Test
    void logicalFlipAppliesOnlyOnConditionLines() {
        String source =
                """
                class Solution {
                    boolean run(boolean a, boolean b) {
                        if (a && b) {
                            return true;
                        }
                        return false;
                    }
                }
                """;
        List<MutationCandidate> candidates = MutationCatalog.candidates(source);

        assertThat(candidates)
                .anySatisfy(c -> {
                    assertThat(c.category()).isEqualTo(Category.LOGICAL_FLIP);
                    assertThat(c.mutatedLine()).contains("a || b");
                });
    }

    @Test
    void commentsAndImportsAreNeverMutated() {
        String source =
                """
                import java.util.List;
                // if (0 < 1) this must never be touched
                class Solution {
                    int run() {
                        return 0;
                    }
                }
                """;
        List<MutationCandidate> candidates = MutationCatalog.candidates(source);

        assertThat(candidates).noneMatch(c -> c.originalLine().contains("import"));
        assertThat(candidates).noneMatch(c -> c.originalLine().strip().startsWith("//"));
    }

    @Test
    void applyToReplacesOnlyTheOneMutatedLine() {
        String source = "class Solution {\n    int x = 0;\n    int y = 0;\n}\n";
        MutationCandidate candidate = MutationCatalog.candidates(source).get(0);

        String mutated = candidate.applyTo(source);

        assertThat(mutated).isNotEqualTo(source);
        assertThat(mutated.split("\n", -1)).hasSize(source.split("\n", -1).length);
    }

    @Test
    void describeNamesTheLineAndTheExactBeforeAfterChange() {
        String source = "class Solution {\n    int x = 0;\n}\n";
        MutationCandidate candidate = MutationCatalog.candidates(source).get(0);

        String description = candidate.describe();

        assertThat(description).contains("Line 2");
        assertThat(description).contains(candidate.originalLine().strip());
        assertThat(description).contains(candidate.mutatedLine().strip());
    }

    @Test
    void candidateOrderIsDeterministicAcrossRepeatedCalls() {
        String source =
                """
                class Solution {
                    int run(int n) {
                        int total = 0;
                        for (int i = 0; i < n; i++) {
                            total = total + 1;
                        }
                        return total;
                    }
                }
                """;
        List<MutationCandidate> first = MutationCatalog.candidates(source);
        List<MutationCandidate> second = MutationCatalog.candidates(source);

        assertThat(first).isEqualTo(second);
        assertThat(first).isNotEmpty();
    }

    @Test
    void everyCandidateActuallyDiffersFromTheOriginalLine() {
        String source =
                """
                class Solution {
                    int run(int n) {
                        int total = 0;
                        for (int i = 0; i < n; i++) {
                            total = total + 1;
                        }
                        return total;
                    }
                }
                """;
        Set<String> unchanged = MutationCatalog.candidates(source).stream()
                .filter(c -> c.originalLine().equals(c.mutatedLine()))
                .map(MutationCandidate::describe)
                .collect(Collectors.toSet());

        assertThat(unchanged).isEmpty();
    }
}
