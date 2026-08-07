package com.sweprep.backend.attempt;

import com.sweprep.backend.grader.Verdict;

/**
 * What a stored {@link Submission}'s recorded outcome is - a superset of the machine
 * {@link Verdict.Outcome}s.
 *
 * <p>Almost every submission is a press of Run whose outcome is a genuine machine verdict
 * ({@link #PASSED} through {@link #ERROR}, one-for-one with {@link Verdict.Outcome}). The
 * one exception is {@link #SELF_RATED}, the marker a self-check "explain in your own words"
 * commit carries (design revision t3, section 1.1): a self-check is <em>never</em>
 * machine-graded, so its submission is deliberately not a {@link Verdict.Outcome} at all.
 * Keeping it a distinct value here - rather than a sixth {@link Verdict.Outcome} - is the
 * type-level half of the boundary the revision is emphatic about: the grader emits no
 * verdict for a self-check, and the record it leaves cannot be mistaken for one. The
 * objective competence signal (issue #38) reads only {@code outcome = 'PASSED'}, so a
 * {@code SELF_RATED} row is structurally invisible to it however the learner self-rates.
 */
public enum SubmissionOutcome {
    /** Every case passed - the clean machine pass the competence signal counts. */
    PASSED,
    /** The code compiled and ran, but not every case passed. */
    FAILED,
    /** The submission did not compile; no case ran. */
    COMPILE_ERROR,
    /** Execution ran past the timeout and was killed. */
    TIMEOUT,
    /** The program failed to run for a reason other than the cases themselves. */
    ERROR,
    /**
     * A self-check produce-then-reveal commit: the learner produced free text, the model
     * answer was revealed, and they self-rated (the rating is stored in the submission's
     * {@code detail}). Never a machine verdict, so never a clean pass; the objective
     * competence signal ignores it by construction.
     */
    SELF_RATED;

    /** The submission outcome for a machine {@link Verdict}. Every verdict name maps one-for-one. */
    public static SubmissionOutcome of(Verdict.Outcome verdict) {
        return valueOf(verdict.name());
    }
}
