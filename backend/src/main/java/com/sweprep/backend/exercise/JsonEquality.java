package com.sweprep.backend.exercise;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Iterator;

/**
 * Structural equality of two JSON values that is <em>numeric-type-agnostic</em>:
 * two numbers that are mathematically equal compare equal however they are
 * written, so a {@code 5} and a {@code 5.0} - or an {@code int} and a {@code long}
 * carrying the same value - are the same answer.
 *
 * <p>This is the shared primitive every {@link Comparison} builds on. Jackson's
 * own {@link JsonNode#equals(Object)} keys on the concrete node type, so an
 * {@code IntNode} never equals a {@code LongNode} or {@code DoubleNode} even when
 * the value is identical - which would tell a user their correct answer is wrong
 * purely because of how a number was represented. Comparing numeric nodes by
 * {@link JsonNode#decimalValue()} removes that trap while staying exact for every
 * non-numeric value.
 */
final class JsonEquality {

    private JsonEquality() {}

    /** Whether {@code a} and {@code b} are the same JSON value, numbers compared by magnitude. */
    static boolean equal(JsonNode a, JsonNode b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a.isNumber() && b.isNumber()) {
            return a.decimalValue().compareTo(b.decimalValue()) == 0;
        }
        if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) {
                return false;
            }
            for (int i = 0; i < a.size(); i++) {
                if (!equal(a.get(i), b.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (a.isObject() && b.isObject()) {
            if (a.size() != b.size()) {
                return false;
            }
            Iterator<String> names = a.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (!b.has(name) || !equal(a.get(name), b.get(name))) {
                    return false;
                }
            }
            return true;
        }
        return a.equals(b);
    }
}
