package com.sweprep.backend.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.sweprep.backend.attempt.RevealResult;
import com.sweprep.backend.grader.FailingCase;

/**
 * The editor's answer to a failing-case reveal (issues #16/#5): the attempt with the
 * reveal recorded, and the disclosed case.
 *
 * <p>{@code failingCase} is omitted from the JSON when there was none to show - the
 * current answer passed, did not compile, timed out, or the exercise is not judged by
 * test cases. The reveal is still recorded on the attempt in that case.
 */
public record RevealResponse(AttemptView attempt, FailingCaseView failingCase) {

    static RevealResponse of(RevealResult result) {
        FailingCase failing = result.failingCase();
        return new RevealResponse(
                AttemptView.of(result.attempt()),
                failing == null ? null : FailingCaseView.of(failing));
    }

    /**
     * One failing case shaped for the editor: the input, the expected value, and what
     * the submission actually produced. {@code actual} and {@code note} are each
     * omitted when absent - a case that produced a value has an {@code actual} and no
     * {@code note}; one that threw has a {@code note} and no {@code actual}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FailingCaseView(JsonNode input, JsonNode expected, JsonNode actual, String note) {

        static FailingCaseView of(FailingCase failing) {
            return new FailingCaseView(
                    failing.input(), failing.expected(), failing.actual(), failing.note());
        }
    }
}
