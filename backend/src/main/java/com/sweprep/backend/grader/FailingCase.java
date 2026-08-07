package com.sweprep.backend.grader;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One failing test case, disclosed only when the solver explicitly asks for it
 * (issues #16/#5). By default a failing submission is told only how many cases
 * failed, with no input, expected or actual values - reasoning about your own code
 * without an oracle is the interview skill being trained. This is what the reveal
 * hands over when the solver chooses to give that up: the case's input, the value it
 * expected, and what the submission actually produced.
 *
 * <p>When the submission produced a value for the case, {@link #actual} holds it and
 * {@link #note} is {@code null}. When the case could not produce a comparable value -
 * the submission threw on it, say - {@link #actual} is {@code null} and {@link #note}
 * explains why, so the reveal is still honest about what happened.
 *
 * @param input    the case's arguments, as language-neutral JSON
 * @param expected the value the case expected back
 * @param actual   what the submission returned, or {@code null} if it produced none
 * @param note     why there is no {@code actual}, or {@code null} when there is one
 */
public record FailingCase(JsonNode input, JsonNode expected, JsonNode actual, String note) {}
