package com.sweprep.backend.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sweprep.backend.attempt.ExplanationResult;

/**
 * The editor's answer to an explanation request (issue #51): the attempt with the
 * request recorded, and the check's explanation.
 *
 * <p>{@code explanation} is omitted from the JSON when the check carries none - the
 * request is still recorded on the attempt, since the solver did ask. Requesting the
 * explanation never reduces a score, blocks completion, or ends the sitting.
 *
 * @param attempt     the attempt with {@code explanationRequested} recorded
 * @param explanation why the correct answer is correct, or {@code null} if none
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExplanationResponse(AttemptView attempt, String explanation) {

    static ExplanationResponse of(ExplanationResult result) {
        return new ExplanationResponse(AttemptView.of(result.attempt()), result.explanation());
    }
}
