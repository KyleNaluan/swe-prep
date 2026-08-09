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
 * size (today, a random {@link Argument.ScalingIntArray}) or one held constant
 * regardless of size (an {@link Argument.Fixed} value, e.g. a search target). The
 * sealed {@link Argument} hierarchy is extended the same way {@link Comparison} is
 * (see the architecture notes) - a new scaling shape is one more permitted record,
 * not a redesign.
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
    public sealed interface Argument permits Argument.ScalingIntArray, Argument.Fixed {

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
