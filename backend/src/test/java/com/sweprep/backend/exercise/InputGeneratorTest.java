package com.sweprep.backend.exercise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.IntNode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Synthesized inputs for empirical scaling measurement (issue #17): one call's
 * positional arguments, matching a {@link TestCase#input}'s shape, produced
 * programmatically instead of hand-authored.
 */
class InputGeneratorTest {

    @Test
    void generatesAScalingArrayOfExactlyTheRequestedSize() {
        InputGenerator generator = new InputGenerator(
                List.of(new InputGenerator.Argument.ScalingIntArray(0, 100)));

        JsonNode call = generator.generate(500, 1L);

        assertThat(call.isArray()).isTrue();
        assertThat(call).hasSize(1);
        assertThat(call.get(0).isArray()).isTrue();
        assertThat(call.get(0)).hasSize(500);
        call.get(0).forEach(n -> assertThat(n.asInt()).isBetween(0, 100));
    }

    @Test
    void aFixedArgumentIsUnaffectedBySize() {
        InputGenerator generator = new InputGenerator(List.of(
                new InputGenerator.Argument.ScalingIntArray(0, 10),
                new InputGenerator.Argument.Fixed(IntNode.valueOf(9))));

        JsonNode small = generator.generate(10, 1L);
        JsonNode large = generator.generate(10_000, 1L);

        assertThat(small.get(1).asInt()).isEqualTo(9);
        assertThat(large.get(1).asInt()).isEqualTo(9);
        assertThat(small.get(0)).hasSize(10);
        assertThat(large.get(0)).hasSize(10_000);
    }

    @Test
    void generationIsDeterministicForTheSameSeed() {
        InputGenerator generator = new InputGenerator(
                List.of(new InputGenerator.Argument.ScalingIntArray(0, 1_000_000)));

        JsonNode first = generator.generate(50, 42L);
        JsonNode second = generator.generate(50, 42L);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void aDifferentSeedProducesADifferentArray() {
        InputGenerator generator = new InputGenerator(
                List.of(new InputGenerator.Argument.ScalingIntArray(0, 1_000_000)));

        JsonNode first = generator.generate(50, 1L);
        JsonNode second = generator.generate(50, 2L);

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void argumentsAreProducedInOrder() {
        InputGenerator generator = new InputGenerator(List.of(
                new InputGenerator.Argument.Fixed(IntNode.valueOf(1)),
                new InputGenerator.Argument.Fixed(IntNode.valueOf(2)),
                new InputGenerator.Argument.Fixed(IntNode.valueOf(3))));

        JsonNode call = generator.generate(10, 1L);

        assertThat(call.get(0).asInt()).isEqualTo(1);
        assertThat(call.get(1).asInt()).isEqualTo(2);
        assertThat(call.get(2).asInt()).isEqualTo(3);
    }

    @Test
    void anEmptyArgumentListIsRejected() {
        assertThatThrownBy(() -> new InputGenerator(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aScalingIntArrayRequiresMinNotExceedingMax() {
        assertThatThrownBy(() -> new InputGenerator.Argument.ScalingIntArray(10, 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- ScalingString (issue #86 follow-on) -------------------------------------------

    @Test
    void generatesAStringOfExactlyTheRequestedSizeOverTheAlphabet() {
        InputGenerator generator =
                new InputGenerator(List.of(new InputGenerator.Argument.ScalingString("ab")));

        JsonNode call = generator.generate(200, 1L);

        String text = call.get(0).asText();
        assertThat(text).hasSize(200);
        assertThat(text).matches("[ab]*");
    }

    @Test
    void aScalingStringIsDeterministicForTheSameSeed() {
        InputGenerator generator =
                new InputGenerator(List.of(new InputGenerator.Argument.ScalingString("abc")));

        assertThat(generator.generate(50, 42L)).isEqualTo(generator.generate(50, 42L));
    }

    @Test
    void aScalingStringDefaultsToLowercaseLettersWhenAskedTo() {
        assertThat(InputGenerator.Argument.ScalingString.DEFAULT_ALPHABET).isEqualTo("abcdefghijklmnopqrstuvwxyz");
    }

    @Test
    void aScalingStringRejectsAnEmptyAlphabet() {
        assertThatThrownBy(() -> new InputGenerator.Argument.ScalingString(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- ScalingIntMatrix (issue #86 follow-on) ----------------------------------------

    @Test
    void generatesASquareMatrixWithTheMeasuredSizeAsBothDimensions() {
        InputGenerator generator =
                new InputGenerator(List.of(new InputGenerator.Argument.ScalingIntMatrix(0, 9)));

        JsonNode call = generator.generate(12, 1L);

        JsonNode matrix = call.get(0);
        assertThat(matrix).hasSize(12);
        matrix.forEach(row -> {
            assertThat(row).hasSize(12);
            row.forEach(cell -> assertThat(cell.asInt()).isBetween(0, 9));
        });
    }

    @Test
    void aScalingIntMatrixIsDeterministicForTheSameSeed() {
        InputGenerator generator =
                new InputGenerator(List.of(new InputGenerator.Argument.ScalingIntMatrix(0, 1000)));

        assertThat(generator.generate(20, 7L)).isEqualTo(generator.generate(20, 7L));
    }

    @Test
    void aScalingIntMatrixRequiresMinNotExceedingMax() {
        assertThatThrownBy(() -> new InputGenerator.Argument.ScalingIntMatrix(10, 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- ScalingListNode (issue #86 follow-on) -----------------------------------------

    @Test
    void generatesAListNodeArgumentOfExactlyTheRequestedSize() {
        InputGenerator generator =
                new InputGenerator(List.of(new InputGenerator.Argument.ScalingListNode(0, 100)));

        JsonNode call = generator.generate(300, 1L);

        JsonNode list = call.get(0);
        assertThat(list.isArray()).isTrue();
        assertThat(list).hasSize(300);
        list.forEach(n -> assertThat(n.asInt()).isBetween(0, 100));
    }

    @Test
    void aScalingListNodeAtSizeZeroIsTheEmptyArray() {
        InputGenerator generator =
                new InputGenerator(List.of(new InputGenerator.Argument.ScalingListNode(0, 100)));

        JsonNode call = generator.generate(0, 1L);

        assertThat(call.get(0)).isEmpty();
    }

    @Test
    void aScalingListNodeIsDeterministicForTheSameSeed() {
        InputGenerator generator =
                new InputGenerator(List.of(new InputGenerator.Argument.ScalingListNode(0, 1_000_000)));

        assertThat(generator.generate(50, 42L)).isEqualTo(generator.generate(50, 42L));
    }

    @Test
    void aScalingListNodeRequiresMinNotExceedingMax() {
        assertThatThrownBy(() -> new InputGenerator.Argument.ScalingListNode(10, 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- ScalingTreeNode (issue #86 follow-on) -----------------------------------------

    @Test
    void generatesATreeNodeArgumentWithExactlyTheRequestedNodeCount() {
        InputGenerator generator =
                new InputGenerator(List.of(new InputGenerator.Argument.ScalingTreeNode(0, 100)));

        JsonNode call = generator.generate(500, 1L);

        JsonNode tree = call.get(0);
        assertThat(tree.isArray()).isTrue();
        long nodeCount = 0;
        for (JsonNode entry : tree) {
            if (!entry.isNull()) {
                nodeCount++;
                assertThat(entry.asInt()).isBetween(0, 100);
            }
        }
        assertThat(nodeCount).isEqualTo(500);
        // The level-order convention (DataType, issue #6): an absent root is [], never
        // [null, ...], and trailing nulls are trimmed.
        assertThat(tree.get(0).isNull()).isFalse();
        assertThat(tree.get(tree.size() - 1).isNull()).isFalse();
    }

    @Test
    void aScalingTreeNodeAtSizeZeroIsTheEmptyArray() {
        InputGenerator generator =
                new InputGenerator(List.of(new InputGenerator.Argument.ScalingTreeNode(0, 100)));

        JsonNode call = generator.generate(0, 1L);

        assertThat(call.get(0)).isEmpty();
    }

    @Test
    void aScalingTreeNodeOfOneNodeIsASingleValueArray() {
        InputGenerator generator =
                new InputGenerator(List.of(new InputGenerator.Argument.ScalingTreeNode(5, 5)));

        JsonNode call = generator.generate(1, 1L);

        assertThat(call.get(0)).hasSize(1);
        assertThat(call.get(0).get(0).asInt()).isEqualTo(5);
    }

    @Test
    void aScalingTreeNodeIsDeterministicForTheSameSeed() {
        InputGenerator generator =
                new InputGenerator(List.of(new InputGenerator.Argument.ScalingTreeNode(0, 1_000_000)));

        assertThat(generator.generate(200, 42L)).isEqualTo(generator.generate(200, 42L));
    }

    @Test
    void aScalingTreeNodeDoesNotDegenerateIntoAStraightLineOnAverage() {
        // A tree built by always descending to the deepest leaf has height == size (a
        // linked list in disguise, at which point an O(n) walk and an O(log n)
        // balanced-tree algorithm take the same number of steps and empirical timing can
        // no longer tell them apart - the exact property ScalingTreeNode's doc comment
        // explains the random-walk construction exists to avoid). A random binary search
        // tree's expected height is ~1.39 * log2(n); for n=2000 that is well under 30, so
        // a generous 10x margin (still two orders of magnitude short of size) is a safe,
        // non-flaky bound across seeds.
        InputGenerator generator =
                new InputGenerator(List.of(new InputGenerator.Argument.ScalingTreeNode(0, 1_000_000)));
        int size = 2000;

        for (long seed = 0; seed < 5; seed++) {
            JsonNode tree = generator.generate(size, seed).get(0);
            assertThat(treeHeight(tree)).isLessThan(200);
        }
    }

    /**
     * Rebuilds only the height, via the same cursor/next walk the harness's own {@code
     * Structures.buildTree} uses (issue #6's convention) - so this measures the actual
     * tree shape the generated JSON encodes, not a re-guessed one.
     */
    private static int treeHeight(JsonNode levelOrder) {
        if (levelOrder.isEmpty()) {
            return 0;
        }
        List<Integer> queue = new java.util.ArrayList<>();
        queue.add(1);
        int cursor = 0;
        int next = 1;
        int maxDepth = 1;
        while (cursor < queue.size() && next < levelOrder.size()) {
            int parentDepth = queue.get(cursor++);
            JsonNode left = levelOrder.get(next++);
            if (!left.isNull()) {
                queue.add(parentDepth + 1);
                maxDepth = Math.max(maxDepth, parentDepth + 1);
            }
            if (next < levelOrder.size()) {
                JsonNode right = levelOrder.get(next++);
                if (!right.isNull()) {
                    queue.add(parentDepth + 1);
                    maxDepth = Math.max(maxDepth, parentDepth + 1);
                }
            }
        }
        return maxDepth;
    }

    @Test
    void aScalingTreeNodeRequiresMinNotExceedingMax() {
        assertThatThrownBy(() -> new InputGenerator.Argument.ScalingTreeNode(10, 5))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
