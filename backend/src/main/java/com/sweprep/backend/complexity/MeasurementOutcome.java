package com.sweprep.backend.complexity;

/**
 * What empirical scaling measurement concluded (issue #17). Exactly one of these
 * three shapes - never a fourth "probably" outcome - because the honesty constraint
 * is a type-level boundary here too: only {@link Conclusive} names a bucket, and
 * even it never says a claim is "correct" - the caller compares its bucket against
 * the claim's own bucket ({@link ComplexityBucket#of}) and words the result as
 * "consistent with" or "not consistent with", never "right" or "wrong".
 */
public sealed interface MeasurementOutcome
        permits MeasurementOutcome.Skipped, MeasurementOutcome.Inconclusive, MeasurementOutcome.Conclusive {

    /**
     * The exercise carries no input generator (optional content metadata by design) -
     * the check was never attempted, and this is not an error (issue #17's explicit
     * acceptance criterion).
     */
    record Skipped() implements MeasurementOutcome {}

    /**
     * Measurement ran but could not confidently classify the growth rate: too fast to
     * time reliably, too close to a bucket boundary, or too few sizes completed cleanly.
     * {@code reason} is shown to the solver verbatim, and it is never worded as a wrong
     * answer - inconclusive is a first-class outcome, not a failure.
     */
    record Inconclusive(String reason) implements MeasurementOutcome {}

    /**
     * Measurement confidently classified the growth rate into one {@link
     * ComplexityBucket}. {@code exponent} is the fitted log-log slope, kept for display
     * and diagnostics only - the bucket, not the raw exponent, is what a claim is judged
     * against.
     */
    record Conclusive(ComplexityBucket bucket, double exponent) implements MeasurementOutcome {}
}
