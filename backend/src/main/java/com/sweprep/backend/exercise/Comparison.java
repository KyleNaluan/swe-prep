package com.sweprep.backend.exercise;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/**
 * How an exercise decides whether a submission's answer counts as correct.
 *
 * <p>Exact JSON equality cannot express the ways two answers can both be right:
 * "any valid pair", "any order", "any of these shortest paths" describe a large
 * share of interview problems. An exercise therefore <em>declares</em> its rule,
 * rather than the grader assuming one and content bending around it (Two Sum used
 * to pin an ordering into its statement purely to make exact equality work; with
 * {@link #orderInsensitiveSequence()} it no longer has to).
 *
 * <p>The set of rules is deliberately a sealed hierarchy, not a fixed enum: a
 * genuinely problem-specific rule is added later by adding one more permitted
 * implementation of {@link #matches}, with no change to the model or the grader.
 * Every rule shares one primitive - {@link JsonEquality}, which compares numbers
 * by magnitude - so all of them are numeric-type-agnostic for free.
 */
public sealed interface Comparison
        permits Comparison.Exact, Comparison.OrderInsensitiveSequence, Comparison.SetEquality {

    /** Whether {@code actual} is an acceptable answer given the {@code expected} value. */
    boolean matches(JsonNode expected, JsonNode actual);

    /** The answer must equal the expected value exactly (numbers still compared by magnitude). */
    static Comparison exact() {
        return Exact.INSTANCE;
    }

    /**
     * The answer is a sequence whose elements must match the expected ones in any
     * order but with the same multiplicity - a multiset. Elements are themselves
     * compared exactly, so an ordered pair inside the sequence keeps its order.
     */
    static Comparison orderInsensitiveSequence() {
        return OrderInsensitiveSequence.INSTANCE;
    }

    /** The answer is a set: the same distinct elements as expected, order and duplicates ignored. */
    static Comparison setEquality() {
        return SetEquality.INSTANCE;
    }

    /** @see #exact() */
    record Exact() implements Comparison {
        private static final Exact INSTANCE = new Exact();

        @Override
        public boolean matches(JsonNode expected, JsonNode actual) {
            return JsonEquality.equal(expected, actual);
        }
    }

    /** @see #orderInsensitiveSequence() */
    record OrderInsensitiveSequence() implements Comparison {
        private static final OrderInsensitiveSequence INSTANCE = new OrderInsensitiveSequence();

        @Override
        public boolean matches(JsonNode expected, JsonNode actual) {
            if (!bothArrays(expected, actual) || expected.size() != actual.size()) {
                return false;
            }
            List<JsonNode> remaining = new ArrayList<>();
            actual.forEach(remaining::add);
            for (JsonNode want : expected) {
                boolean matched = false;
                for (int i = 0; i < remaining.size(); i++) {
                    if (JsonEquality.equal(want, remaining.get(i))) {
                        remaining.remove(i);
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    return false;
                }
            }
            return true;
        }
    }

    /** @see #setEquality() */
    record SetEquality() implements Comparison {
        private static final SetEquality INSTANCE = new SetEquality();

        @Override
        public boolean matches(JsonNode expected, JsonNode actual) {
            if (!bothArrays(expected, actual)) {
                return false;
            }
            return coversEvery(actual, expected) && coversEvery(expected, actual);
        }

        /** Whether every element of {@code needles} appears somewhere in {@code haystack}. */
        private static boolean coversEvery(JsonNode haystack, JsonNode needles) {
            for (JsonNode needle : needles) {
                boolean found = false;
                for (JsonNode candidate : haystack) {
                    if (JsonEquality.equal(needle, candidate)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    return false;
                }
            }
            return true;
        }
    }

    private static boolean bothArrays(JsonNode a, JsonNode b) {
        return a != null && b != null && a.isArray() && b.isArray();
    }
}
