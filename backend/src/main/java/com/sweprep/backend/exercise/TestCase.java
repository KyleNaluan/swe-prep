package com.sweprep.backend.exercise;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One language-neutral test case: the arguments to pass and the value expected
 * back, both as plain JSON data with no language-specific syntax.
 *
 * @param input    a JSON array of positional arguments, one per {@link Signature}
 *                 parameter, in call order
 * @param expected the JSON value the submission's return value must equal
 */
public record TestCase(JsonNode input, JsonNode expected) {}
