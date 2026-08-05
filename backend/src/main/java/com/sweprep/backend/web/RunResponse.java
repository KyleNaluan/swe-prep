package com.sweprep.backend.web;

import com.sweprep.backend.grader.Verdict;

/**
 * The verdict, shaped for the editor.
 *
 * @param outcome one of the {@link Verdict.Outcome} names
 * @param passed  number of passing cases
 * @param total   number of cases
 * @param detail  compiler diagnostics or a timeout note, otherwise empty
 */
public record RunResponse(String outcome, int passed, int total, String detail) {

    static RunResponse of(Verdict verdict) {
        return new RunResponse(
                verdict.outcome().name(), verdict.passed(), verdict.total(), verdict.detail());
    }
}
