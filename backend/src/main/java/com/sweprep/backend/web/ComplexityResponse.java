package com.sweprep.backend.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sweprep.backend.attempt.ComplexityClaimResult;
import com.sweprep.backend.complexity.ComplexityClassifier;
import com.sweprep.backend.complexity.MeasurementOutcome;
import java.util.List;

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
 * @param attempt              the attempt with the claim, measured complexity and match flag
 * @param targetTime           the authored time complexity
 * @param targetSpace          the authored space complexity (self-reported only - never checked)
 * @param status               the coarse measurement outcome
 * @param detail               why measurement was inconclusive, or {@code null} otherwise
 * @param modelOpinionAvailable whether the LLM complexity second opinion (issue #83) can
 *                             be requested from here - {@code false} whenever no advisor
 *                             is configured, so the editor can hide the action entirely
 *                             rather than offer a button that would fail
 * @param exponent             the fitted log-log slope, or {@code null} unless {@code
 *                             status} is a measured one ({@code CONSISTENT}/{@code
 *                             CONTRADICTED}) - the Direction A graft's plot draws from
 *                             this rather than re-measuring or re-deriving anything
 *                             client-side
 * @param confidenceHalfWidth  the slope's confidence interval half-width, {@code null}
 *                             under the same condition as {@code exponent}
 * @param points               the (input size, measured milliseconds) points the fit
 *                             was drawn from, {@code null} under the same condition
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ComplexityResponse(
        AttemptView attempt,
        String targetTime,
        String targetSpace,
        String status,
        String detail,
        boolean modelOpinionAvailable,
        Double exponent,
        Double confidenceHalfWidth,
        List<MeasurementPoint> points) {

    /** One measured point on the log-log plot: an input size and its runtime in milliseconds. */
    public record MeasurementPoint(int size, double millis) {}

    static ComplexityResponse of(ComplexityClaimResult result, boolean modelOpinionAvailable) {
        Boolean claimCorrect = result.attempt().attempt().complexityClaimCorrect();
        String status;
        String detail = null;
        Double exponent = null;
        Double confidenceHalfWidth = null;
        List<MeasurementPoint> points = null;
        switch (result.measurement()) {
            case MeasurementOutcome.Skipped ignored -> status = "SKIPPED";
            case MeasurementOutcome.Inconclusive inconclusive -> {
                status = "INCONCLUSIVE";
                detail = inconclusive.reason();
            }
            case MeasurementOutcome.Conclusive conclusive -> {
                status = Boolean.TRUE.equals(claimCorrect) ? "CONSISTENT" : "CONTRADICTED";
                exponent = conclusive.exponent();
                confidenceHalfWidth = conclusive.confidenceHalfWidth();
                points = conclusive.points().stream()
                        .map(ComplexityResponse::toMeasurementPoint)
                        .toList();
            }
        }
        return new ComplexityResponse(
                AttemptView.of(result.attempt()),
                result.targetTime().name(),
                result.targetSpace().name(),
                status,
                detail,
                modelOpinionAvailable,
                exponent,
                confidenceHalfWidth,
                points);
    }

    private static MeasurementPoint toMeasurementPoint(ComplexityClassifier.SizeTiming point) {
        return new MeasurementPoint(point.size(), point.nanos() / 1_000_000.0);
    }
}
