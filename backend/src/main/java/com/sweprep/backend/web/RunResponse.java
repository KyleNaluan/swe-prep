package com.sweprep.backend.web;

import com.sweprep.backend.attempt.Submission;
import com.sweprep.backend.grader.Verdict;

/**
 * The verdict, shaped for the editor.
 *
 * <p>{@code runtimeMillis} is shown for interest only; it is never part of the
 * verdict (issue #16/#5). The editor never reports a failing case's input, expected
 * or actual value here - a failing verdict carries only the passing count, and the
 * failing case is disclosed only through an explicit reveal.
 *
 * @param outcome       one of the {@link Verdict.Outcome} names
 * @param passed        number of passing cases
 * @param total         number of cases
 * @param detail        compiler diagnostics or a timeout note, otherwise empty
 * @param runtimeMillis how long the run took, for display only
 */
public record RunResponse(String outcome, int passed, int total, String detail, long runtimeMillis) {

    static RunResponse of(Verdict verdict) {
        return new RunResponse(
                verdict.outcome().name(),
                verdict.passed(),
                verdict.total(),
                verdict.detail(),
                verdict.runtimeMillis());
    }

    /** The same verdict shape, read from a persisted submission. */
    static RunResponse of(Submission submission) {
        return new RunResponse(
                submission.outcome().name(),
                submission.passed(),
                submission.total(),
                submission.detail(),
                submission.runtimeMillis());
    }
}
