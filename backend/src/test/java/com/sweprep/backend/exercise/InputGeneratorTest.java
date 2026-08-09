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
}
