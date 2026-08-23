package com.sweprep.backend.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.sweprep.backend.exercise.DataType;
import com.sweprep.backend.exercise.Signature;
import com.sweprep.backend.exercise.Signature.Parameter;
import java.util.List;

/**
 * Load-time validation of the values an exercise authors for a {@link
 * DataType#LIST_NODE} or {@link DataType#TREE_NODE} slot.
 *
 * <p>A case's arguments are otherwise untyped JSON: nothing else in the loader checks
 * that {@code [1,2,3]} is what a parameter wanted, because for the value types a
 * mismatch surfaces immediately and harmlessly as a per-case failure. A linked
 * structure is different - the harness has to <em>build</em> from the value, so a
 * mistyped case (an array of strings, a {@code pos} past the end of the list, a tree
 * whose first entry is null) would surface as an opaque "the submission threw" against
 * a solution that is in fact correct. Catching it here instead means the failure names
 * the file, the field and the rule, exactly like every other content error (issue #14).
 *
 * <p>Deliberately scoped to signatures that actually declare one of these types, so no
 * previously loadable content can start failing.
 */
final class NodeShapes {

    private NodeShapes() {}

    /**
     * Validates every argument and expected value a linked-structure signature governs.
     * A no-op for a signature that declares none.
     */
    static void validate(ContentJson json, Signature signature, List<JsonNode> inputs, JsonNode expected) {
        List<Parameter> parameters = signature.parameters();
        boolean declaresStructure = signature.returnType().isLinkedStructure()
                || parameters.stream().anyMatch(p -> p.type().isLinkedStructure());
        if (!declaresStructure) {
            return;
        }
        for (JsonNode input : inputs) {
            if (!input.isArray() || input.size() != parameters.size()) {
                throw json.malformed(
                        "each case's 'input' must be an array of exactly one argument per signature "
                                + "parameter (" + parameters.size() + ")");
            }
            for (int i = 0; i < parameters.size(); i++) {
                Parameter parameter = parameters.get(i);
                if (parameter.type().isLinkedStructure()) {
                    validateArgument(json, parameter, input.get(i));
                }
            }
        }
        if (expected != null && signature.returnType().isLinkedStructure()) {
            validateReturned(json, signature.returnType(), expected);
        }
    }

    /**
     * An argument may use the richer input forms: a list may be posed with a cycle the
     * LeetCode way ({@code {"values": [...], "pos": k}}), since the solver is handed the
     * built structure and never sees the JSON.
     */
    private static void validateArgument(ContentJson json, Parameter parameter, JsonNode value) {
        String where = "argument '" + parameter.name() + "'";
        if (parameter.type() == DataType.TREE_NODE) {
            requireLevelOrder(json, where, value);
            return;
        }
        if (value == null || value.isNull() || value.isArray()) {
            requireIntArray(json, where, value);
            return;
        }
        if (!value.isObject()) {
            throw json.malformed(
                    where + " must be a LIST_NODE: an array of integers, or "
                            + "{ \"values\": [...], \"pos\": k } to pose a cycle");
        }
        JsonNode values = value.get("values");
        if (values == null || !values.isArray()) {
            throw json.malformed(where + " uses the cycle form but has no 'values' array");
        }
        requireIntArray(json, where + "'s 'values'", values);
        JsonNode pos = value.get("pos");
        if (pos == null || pos.isNull()) {
            return;
        }
        if (!pos.isIntegralNumber()) {
            throw json.malformed(where + "'s 'pos' must be an integer index, or -1 for no cycle");
        }
        int index = pos.asInt();
        if (index >= values.size()) {
            throw json.malformed(
                    where + "'s 'pos' is " + index + ", past the end of a list of " + values.size()
                            + " values (use -1 for no cycle)");
        }
    }

    /**
     * A returned value is always the plain serialised form: a linked structure that came
     * back out of a submission is acyclic by construction, so the cycle input form is not
     * accepted here.
     */
    private static void validateReturned(ContentJson json, DataType returnType, JsonNode expected) {
        String where = "the expected return value";
        if (returnType == DataType.TREE_NODE) {
            requireLevelOrder(json, where, expected);
        } else {
            requireIntArray(json, where, expected);
        }
    }

    /** A LIST_NODE in serialised form: null, or an array of integers, with no holes. */
    private static void requireIntArray(ContentJson json, String where, JsonNode value) {
        if (value == null || value.isNull()) {
            return;
        }
        if (!value.isArray()) {
            throw json.malformed(where + " must be a LIST_NODE: an array of integers, or null");
        }
        for (JsonNode element : value) {
            if (!element.isIntegralNumber()) {
                throw json.malformed(
                        where + " must contain only integers - a linked list has no absent elements");
            }
        }
    }

    /**
     * A TREE_NODE: null, or LeetCode's level-order array whose entries are integers or
     * nulls. A leading null is rejected because it cannot mean anything - an absent root
     * is the empty array.
     */
    private static void requireLevelOrder(ContentJson json, String where, JsonNode value) {
        if (value == null || value.isNull()) {
            return;
        }
        if (!value.isArray()) {
            throw json.malformed(
                    where + " must be a TREE_NODE: a level-order array such as "
                            + "[3, 9, 20, null, null, 15, 7], or null");
        }
        for (JsonNode element : value) {
            if (!element.isIntegralNumber() && !element.isNull()) {
                throw json.malformed(
                        where + " must contain only integers and nulls - a null is an absent child");
            }
        }
        if (!value.isEmpty() && value.get(0).isNull()) {
            throw json.malformed(
                    where + " starts with null; an absent root is the empty array [], not [null, ...]");
        }
    }
}
