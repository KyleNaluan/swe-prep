package com.sweprep.backend.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sweprep.backend.attempt.ComplexityClaimResult;
import com.sweprep.backend.complexity.MeasurementOutcome;

/**
 * The editor's answer to a complexity claim (issue #17): the attempt with the claim
 * and measurement now recorded, the authored target - revealed here for the first
 * time, never before this call - and the empirical measurement status.
 *
 * <p>{@code status} is one of {@code "CONSISTENT"}, {@code "CONTRADICTED"}, {@code
 * "INCONCLUSIVE"} or {@code "SKIPPED"} (no input generator). Per the honesty
 * constraint (issue #17), the editor's copy for {@code "CONSISTENT"} reads "measured
 * scaling is consistent with your claim" - never "correct" - and {@code
 * "INCONCLUSIVE"} is shown as its own explicit outcome, never silently folded into
 * either match state. {@code detail} carries why measurement was inconclusive, and is
 * omitted otherwise.
 *
 * @param attempt     the attempt with the claim, measured complexity and match flag
 * @param targetTime  the authored time complexity
 * @param targetSpace the authored space complexity (self-reported only - never checked)
 * @param status      the coarse measurement outcome
 * @param detail      why measurement was inconclusive, or {@code null} otherwise
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ComplexityResponse(
        AttemptView attempt, String targetTime, String targetSpace, String status, String detail) {

    static ComplexityResponse of(ComplexityClaimResult result) {
        Boolean claimCorrect = result.attempt().attempt().complexityClaimCorrect();
        String status;
        String detail = null;
        switch (result.measurement()) {
            case MeasurementOutcome.Skipped ignored -> status = "SKIPPED";
            case MeasurementOutcome.Inconclusive inconclusive -> {
                status = "INCONCLUSIVE";
                detail = inconclusive.reason();
            }
            case MeasurementOutcome.Conclusive ignored ->
                    status = Boolean.TRUE.equals(claimCorrect) ? "CONSISTENT" : "CONTRADICTED";
        }
        return new ComplexityResponse(
                AttemptView.of(result.attempt()),
                result.targetTime().name(),
                result.targetSpace().name(),
                status,
                detail);
    }
}
