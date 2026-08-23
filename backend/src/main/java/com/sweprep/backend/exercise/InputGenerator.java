package com.sweprep.backend.exercise;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.Random;

/**
 * Optional content metadata (issue #17) describing how to synthesize one call's
 * positional arguments at a given input size, for empirical scaling measurement.
 * Language-neutral, like a {@link TestCase}'s {@code input}: the shape {@link
 * #generate} produces is exactly a {@code TestCase.input} array (one entry per
 * {@link Signature} parameter, in call order), just synthesized programmatically
 * instead of hand-authored.
 *
 * <p>Each argument scales independently: a parameter that grows with the measured
 * size ({@link Argument.ScalingIntArray}, {@link Argument.ScalingString}, {@link
 * Argument.ScalingIntMatrix}, {@link Argument.ScalingListNode}, {@link
 * Argument.ScalingTreeNode}) or one held constant regardless of size (an {@link
 * Argument.Fixed} value, e.g. a search target). The sealed {@link Argument}
 * hierarchy is extended the same way {@link Comparison} is (see the architecture
 * notes) - a new scaling shape is one more permitted record, not a redesign; the
 * four structural kinds beyond the original {@code ScalingIntArray} (issue #26's
 * follow-on) are what let the content set's STRING, INT_MATRIX, LIST_NODE and
 * TREE_NODE problems carry the same empirical scaling check the array ones already
 * had.
 *
 * <p><strong>O(size) memory invariant.</strong> Every {@code Argument} kind's {@link
 * Argument#generate} must allocate {@code O(size)} memory. {@code ScalingMeasurer}
 * drives every kind in an exercise's generator through one shared global {@code
 * sweprep.complexity.sizes} list (default up to 32000) with no per-exercise or
 * per-kind override, and generation runs in-process in the backend JVM <em>before</em>
 * {@code runner.execute} - so it is not protected by the runner's isolation or timeout
 * the way a submitted solution is, and a super-linear allocation (e.g. a {@code size} x
 * {@code size} matrix) would exhaust the backend heap on the claim path rather than
 * collapsing to an inconclusive measurement. This is the rule any future scaling kind
 * must keep.
 *
 * @param arguments one generator per signature parameter, in call order
 */
public record InputGenerator(List<Argument> arguments) {

    public InputGenerator {
        arguments = List.copyOf(arguments);
        if (arguments.isEmpty()) {
            throw new IllegalArgumentException(
                    "an input generator must describe at least one argument");
        }
    }

    /**
     * Synthesizes one call's positional arguments at {@code size}, deterministically
     * from {@code seed} - so measuring the same exercise at the same size twice (e.g.
     * a retried claim) generates the same inputs rather than a fresh random draw.
     */
    public JsonNode generate(int size, long seed) {
        Random random = new Random(seed);
        ArrayNode call = JsonNodeFactory.instance.arrayNode();
        for (Argument argument : arguments) {
            call.add(argument.generate(size, random));
        }
        return call;
    }

    /** How one argument's value is produced at a given measured size. */
    public sealed interface Argument
            permits Argument.ScalingIntArray, Argument.ScalingString, Argument.ScalingIntMatrix,
                    Argument.ScalingListNode, Argument.ScalingTreeNode, Argument.Fixed {

        JsonNode generate(int size, Random random);

        /**
         * A parameter that grows with the measured size: an array of {@code size} random
         * ints drawn uniformly from [{@code min}, {@code max}]. Only fits a parameter
         * declared {@link DataType#INT_ARRAY} in the exercise's signature - validated at
         * content-load time ({@code ExerciseParser}), not here, since this type does not
         * see the signature.
         */
        record ScalingIntArray(int min, int max) implements Argument {

            public ScalingIntArray {
                if (min > max) {
                    throw new IllegalArgumentException("min must not exceed max");
                }
            }

            @Override
            public JsonNode generate(int size, Random random) {
                ArrayNode array = JsonNodeFactory.instance.arrayNode();
                long span = (long) max - min + 1;
                for (int i = 0; i < size; i++) {
                    array.add(min + (int) (random.nextDouble() * span));
                }
                return array;
            }
        }

        /**
         * A parameter that grows with the measured size: a string of {@code size}
         * characters, each drawn uniformly from {@code alphabet}. Only fits a parameter
         * declared {@link DataType#STRING} - validated at content-load time ({@code
         * ExerciseParser}), same as {@link ScalingIntArray}. {@code alphabet} defaults to
         * lowercase a-z ({@link #DEFAULT_ALPHABET}) when the content omits it, resolved by
         * the parser so this record itself always carries an explicit, non-empty alphabet.
         */
        record ScalingString(String alphabet) implements Argument {

            /** The default alphabet when content omits {@code "alphabet"}: lowercase a-z. */
            public static final String DEFAULT_ALPHABET = "abcdefghijklmnopqrstuvwxyz";

            public ScalingString {
                java.util.Objects.requireNonNull(alphabet, "alphabet");
                if (alphabet.isEmpty()) {
                    throw new IllegalArgumentException("alphabet must not be empty");
                }
            }

            @Override
            public JsonNode generate(int size, Random random) {
                StringBuilder text = new StringBuilder(size);
                for (int i = 0; i < size; i++) {
                    text.append(alphabet.charAt(random.nextInt(alphabet.length())));
                }
                return JsonNodeFactory.instance.textNode(text.toString());
            }
        }

        /**
         * A parameter that grows with the measured size: a matrix of {@code size} rows,
         * each of a fixed {@code cols} columns, of random ints drawn uniformly from
         * [{@code min}, {@code max}]. Only fits a parameter declared {@link
         * DataType#INT_MATRIX}. The measured size drives the <em>row count only</em> -
         * columns stay fixed at {@code cols} (default {@link #DEFAULT_COLS}) - so total
         * cells grow {@code O(size)}, keeping this kind inside the {@code O(size)} memory
         * invariant documented on {@link InputGenerator}. A quoted {@code targetTime}/{@code
         * targetSpace} on an exercise using this kind therefore states its complexity in
         * terms of row count only, not total cell count: a stated {@code LINEAR} target
         * means linear in rows, and a {@code QUADRATIC} target means an algorithm that
         * revisits rows/cells, not that the input itself grows quadratically.
         */
        record ScalingIntMatrix(int min, int max, int cols) implements Argument {

            /** The default column count when content omits {@code "cols"}. */
            public static final int DEFAULT_COLS = 8;

            public ScalingIntMatrix {
                if (min > max) {
                    throw new IllegalArgumentException("min must not exceed max");
                }
                if (cols < 1) {
                    throw new IllegalArgumentException("cols must be at least 1");
                }
            }

            public ScalingIntMatrix(int min, int max) {
                this(min, max, DEFAULT_COLS);
            }

            @Override
            public JsonNode generate(int size, Random random) {
                ArrayNode matrix = JsonNodeFactory.instance.arrayNode();
                long span = (long) max - min + 1;
                for (int row = 0; row < size; row++) {
                    ArrayNode line = JsonNodeFactory.instance.arrayNode();
                    for (int col = 0; col < cols; col++) {
                        line.add(min + (int) (random.nextDouble() * span));
                    }
                    matrix.add(line);
                }
                return matrix;
            }
        }

        /**
         * A parameter that grows with the measured size: a linked list of {@code size}
         * random ints drawn uniformly from [{@code min}, {@code max}]. Only fits a
         * parameter declared {@link DataType#LIST_NODE}. Always the plain-array serialised
         * form ({@code DataType}'s convention) - deliberately acyclic only, never the
         * {@code {values, pos}} cycle form, matching {@link ScalingIntArray}'s "no
         * constrained distribution beyond what this record states" scope.
         */
        record ScalingListNode(int min, int max) implements Argument {

            public ScalingListNode {
                if (min > max) {
                    throw new IllegalArgumentException("min must not exceed max");
                }
            }

            @Override
            public JsonNode generate(int size, Random random) {
                ArrayNode list = JsonNodeFactory.instance.arrayNode();
                long span = (long) max - min + 1;
                for (int i = 0; i < size; i++) {
                    list.add(min + (int) (random.nextDouble() * span));
                }
                return list;
            }
        }

        /**
         * A parameter that grows with the measured size: a binary tree of {@code size}
         * nodes, each value drawn uniformly from [{@code min}, {@code max}], serialised in
         * the level-order-with-nulls form {@link DataType#TREE_NODE} defines. Only fits a
         * parameter declared {@code TREE_NODE}.
         *
         * <p>Shape: each of the {@code size} nodes is inserted by a random walk from the
         * root - at each existing node it visits, it turns left or right with equal
         * probability, and is placed the first time that turn reaches an empty slot. This
         * is the same construction that produces a random binary search tree in
         * distribution (turning left/right uniformly at random is equivalent, in shape, to
         * comparing against a uniformly random key at each node), whose well-known expected
         * height is {@code O(log n)}. That is the deliberate reason for choosing this
         * construction over, say, always descending to the deepest existing leaf: an
         * always-deepest insertion degenerates into a straight-line list, at which point an
         * {@code O(n)} tree walk and an {@code O(log n)} balanced-tree algorithm take the
         * same number of steps and empirical timing can no longer tell them apart. This
         * generator does not guarantee a balanced tree on any single draw - only that it
         * does not degenerate on average, which is what a random {@code (size, seed)} draw
         * needs to keep the two algorithm shapes distinguishable across measurements.
         */
        record ScalingTreeNode(int min, int max) implements Argument {

            public ScalingTreeNode {
                if (min > max) {
                    throw new IllegalArgumentException("min must not exceed max");
                }
            }

            @Override
            public JsonNode generate(int size, Random random) {
                if (size <= 0) {
                    return JsonNodeFactory.instance.arrayNode();
                }
                long span = (long) max - min + 1;
                Slot root = new Slot(min + (int) (random.nextDouble() * span));
                for (int i = 1; i < size; i++) {
                    int value = min + (int) (random.nextDouble() * span);
                    insert(root, value, random);
                }
                return levelOrder(root);
            }

            private static void insert(Slot root, int value, Random random) {
                Slot current = root;
                while (true) {
                    if (random.nextBoolean()) {
                        if (current.left == null) {
                            current.left = new Slot(value);
                            return;
                        }
                        current = current.left;
                    } else {
                        if (current.right == null) {
                            current.right = new Slot(value);
                            return;
                        }
                        current = current.right;
                    }
                }
            }

            /**
             * Breadth-first walk to the level-order-with-nulls array, trailing nulls
             * trimmed - the same walk {@code Structures.serializeTree} uses on the Java
             * side, including its reason for a plain, index-walked list rather than a
             * {@code Deque}: an absent child is enqueued as {@code null}, which a
             * {@code Deque} refuses to hold.
             */
            private static JsonNode levelOrder(Slot root) {
                List<Slot> queue = new java.util.ArrayList<>();
                queue.add(root);
                List<Integer> values = new java.util.ArrayList<>();
                for (int i = 0; i < queue.size(); i++) {
                    Slot node = queue.get(i);
                    if (node == null) {
                        values.add(null);
                        continue;
                    }
                    values.add(node.value);
                    queue.add(node.left);
                    queue.add(node.right);
                }
                int end = values.size();
                while (end > 0 && values.get(end - 1) == null) {
                    end--;
                }
                ArrayNode trimmed = JsonNodeFactory.instance.arrayNode();
                for (int i = 0; i < end; i++) {
                    Integer value = values.get(i);
                    if (value == null) {
                        trimmed.addNull();
                    } else {
                        trimmed.add(value.intValue());
                    }
                }
                return trimmed;
            }

            /** A plain mutable tree node used only while building - never exposed. */
            private static final class Slot {
                private final int value;
                private Slot left;
                private Slot right;

                private Slot(int value) {
                    this.value = value;
                }
            }
        }

        /**
         * A parameter held constant regardless of the measured size, e.g. a fixed search
         * target that would otherwise change what the growing array means.
         */
        record Fixed(JsonNode value) implements Argument {

            public Fixed {
                java.util.Objects.requireNonNull(value, "value");
            }

            @Override
            public JsonNode generate(int size, Random random) {
                return value;
            }
        }
    }
}
