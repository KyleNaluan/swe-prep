package com.sweprep.backend.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sweprep.backend.attempt.ReferenceSolutionResult;

/**
 * The editor's answer to a reference-solution reveal (issue #82): the attempt (with
 * {@code solutionSeen} recorded when this was a pre-pass reveal), the solution text,
 * and whether this reveal happened before the attempt had passed - the distinction the
 * editor's pre-pass/post-pass presentation reads.
 *
 * <p>{@code solution} is omitted from the JSON when the exercise carries none to
 * reveal - not every exercise is code-response, or content has none authored yet.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReferenceSolutionResponse(AttemptView attempt, String solution, boolean prePass) {

    static ReferenceSolutionResponse of(ReferenceSolutionResult result) {
        return new ReferenceSolutionResponse(
                AttemptView.of(result.attempt()), result.solution(), result.prePass());
    }
}
