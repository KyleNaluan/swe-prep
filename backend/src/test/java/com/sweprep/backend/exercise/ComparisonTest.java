package com.sweprep.backend.exercise;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * The comparison rules an exercise can declare, and the numeric-equality fix they
 * all share: exact keeps structure and order, order-insensitive treats a sequence
 * as a multiset, set ignores order and duplicates, and none of them is fooled by
 * how a number happens to be written.
 */
class ComparisonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String raw) {
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException("bad test json: " + raw, e);
        }
    }

    @Test
    void exactRequiresSameStructureAndOrder() {
        Comparison exact = Comparison.exact();

        assertThat(exact.matches(json("[0, 1]"), json("[0, 1]"))).isTrue();
        assertThat(exact.matches(json("[0, 1]"), json("[1, 0]"))).isFalse();
        assertThat(exact.matches(json("{\"a\": 1}"), json("{\"a\": 1}"))).isTrue();
    }

    @Test
    void orderInsensitiveSequenceIsAMultiset() {
        Comparison rule = Comparison.orderInsensitiveSequence();

        assertThat(rule.matches(json("[0, 1]"), json("[1, 0]"))).isTrue();
        // Same elements, different multiplicity, so not a match.
        assertThat(rule.matches(json("[3, 3]"), json("[3]"))).isFalse();
        assertThat(rule.matches(json("[1, 2, 2]"), json("[2, 1, 2]"))).isTrue();
        // Elements keep their own order: an ordered pair inside is compared exactly.
        assertThat(rule.matches(json("[[0, 1], [2, 3]]"), json("[[2, 3], [0, 1]]"))).isTrue();
        assertThat(rule.matches(json("[[0, 1]]"), json("[[1, 0]]"))).isFalse();
    }

    @Test
    void setEqualityIgnoresOrderAndDuplicates() {
        Comparison rule = Comparison.setEquality();

        assertThat(rule.matches(json("[1, 2, 3]"), json("[3, 2, 1]"))).isTrue();
        assertThat(rule.matches(json("[1, 2, 2]"), json("[2, 1]"))).isTrue();
        assertThat(rule.matches(json("[1, 2]"), json("[1, 2, 3]"))).isFalse();
    }

    @Test
    void numericallyEqualAnswersMatchHoweverTheNumberIsWritten() {
        // A whole number written as an int and as a decimal. Jackson's own node
        // equality - what the harness used to compare with - keys on the concrete
        // node type and rejects this pair outright, which is exactly the bug: a
        // correct answer graded wrong purely for how the number was represented.
        JsonNode asInt = json("[5]");
        JsonNode asDecimal = json("[5.0]");
        assertThat(asInt.equals(asDecimal)).isFalse();

        assertThat(Comparison.exact().matches(asInt, asDecimal)).isTrue();
        assertThat(Comparison.orderInsensitiveSequence().matches(json("[5, 6]"), json("[6.0, 5.0]")))
                .isTrue();
    }

    @Test
    void aMissingOrNonArrayAnswerNeverMatchesASequenceRule() {
        assertThat(Comparison.orderInsensitiveSequence().matches(json("[0, 1]"), json("5"))).isFalse();
        assertThat(Comparison.setEquality().matches(json("[0, 1]"), json("null"))).isFalse();
    }
}
