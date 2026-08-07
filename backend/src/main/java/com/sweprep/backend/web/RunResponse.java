package com.sweprep.backend.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sweprep.backend.attempt.SubmitResult;

/**
 * The verdict, shaped for the editor.
 *
 * <p>{@code runtimeMillis} is shown for interest only; it is never part of the
 * verdict (issue #16/#5). The editor never reports a failing case's input, expected
 * or actual value here - a failing verdict carries only the passing count, and the
 * failing case is disclosed only through an explicit reveal.
 *
 * <p>{@code explanation} is the one exception to withholding-by-default (issue #51): on
 * a wrong answer the check's explanation of why the correct answer is correct is
 * disclosed automatically, so it travels here. It is omitted from the JSON otherwise -
 * a passing answer offers the explanation on request instead, an execution problem is
 * not a wrong answer, and a check may carry no explanation at all.
 *
 * @param outcome       one of the {@link com.sweprep.backend.grader.Verdict.Outcome} names
 * @param passed        number of passing cases
 * @param total         number of cases
 * @param detail        compiler diagnostics or a timeout note, otherwise empty
 * @param runtimeMillis how long the run took, for display only
 * @param explanation   why the correct answer is correct, shown automatically on a wrong
 *                      answer; {@code null} (and omitted) otherwise
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunResponse(
        String outcome, int passed, int total, String detail, long runtimeMillis, String explanation) {

    /** The stored submission's verdict, with the explanation to show on a wrong answer. */
    static RunResponse of(SubmitResult result) {
        var submission = result.submission();
        return new RunResponse(
                submission.outcome().name(),
                submission.passed(),
                submission.total(),
                submission.detail(),
                submission.runtimeMillis(),
                result.explanation());
    }
}
