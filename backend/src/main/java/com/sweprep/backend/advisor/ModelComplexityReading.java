package com.sweprep.backend.advisor;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.sweprep.backend.exercise.Complexity;

/**
 * A model's independent reading of a submission's time complexity (issue #83): the
 * tightest asymptotic class it settles on, from the same closed {@link Complexity}
 * vocabulary the solver's own self-report uses, plus the reasoning behind it - asked
 * for in the same call, since the reasoning is exactly what the disagreement prompt
 * shows the learner (never the bucket alone, which would read as an unexplained
 * verdict). This is the shape {@link AnthropicComplexityAdvisor} asks the model to
 * fill via structured output, so both fields are Jackson-schema-derived from this
 * record - no hand-written JSON schema or free-text parsing.
 *
 * @param time      the model's reading of the tightest time complexity class
 * @param reasoning a short, specific explanation naming the operations that drive it
 */
public record ModelComplexityReading(
        @JsonPropertyDescription(
                "The tightest asymptotic time complexity class this code exhibits, as a function "
                        + "of its input size.")
                Complexity time,
        @JsonPropertyDescription(
                "A short, specific explanation of why this complexity class applies. Name the "
                        + "actual operations that drive the cost - loops, recursion, and any "
                        + "library calls whose own cost is not O(1) (e.g. string concatenation, "
                        + "List.contains, substring) - rather than a generic restatement of the "
                        + "verdict.")
                String reasoning) {}
