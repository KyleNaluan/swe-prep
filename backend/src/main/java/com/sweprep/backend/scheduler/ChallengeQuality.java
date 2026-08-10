package com.sweprep.backend.scheduler;

/**
 * Reduces one completed {@code CHALLENGE}-form attempt to the 0-5 quality score the
 * challenge priority scorer consumes (issue #21, issue #8's decision that an attempt
 * record "collapses to a single 0-5 quality score rather than feeding raw fields into
 * either algorithm"). Kept as its own small, directly testable unit rather than buried
 * inside the priority arithmetic - the same shape as {@link ReviewQuality}, the sibling
 * derivation issue #20 built for reps. The two are deliberately separate classes: a
 * challenge's judging record carries signals (submission count, help taken, the
 * complexity claim) a rep's never does, so there is no shared input shape to unify them
 * behind without one of the two callers passing fields that mean nothing to it. Both
 * exist in this package as the two 0-5 derivations over one shared attempt record
 * (decision #8's "two schedulers, one signal" framing); a future third form would be the
 * signal to extract a common strategy interface, not before.
 *
 * <p>The three levels are exactly the ones issue #8's resolution names:
 *
 * <ul>
 *   <li>Passed on the first submission, with no hint taken, no failing-case reveal, and
 *       (when the exercise checks it) a complexity claim measurement did not contradict
 *       -&gt; {@link #PERFECT}.
 *   <li>Passed, but climbed the hint ladder, revealed the failing case, or took more
 *       than one submission to get there -&gt; {@link #WEAK_PASS}.
 *   <li>Failed or abandoned -&gt; {@link #INCORRECT}.
 * </ul>
 */
public final class ChallengeQuality {

    /** A clean first-submission pass with no help taken: the top mark. */
    public static final int PERFECT = 5;

    /** A pass that needed more than one try, a hint, or the failing case revealed. */
    public static final int WEAK_PASS = 3;

    /** A failed or abandoned attempt: the bottom mark, whatever else happened. */
    public static final int INCORRECT = 1;

    private ChallengeQuality() {}

    /**
     * The 0-5 quality score for one terminal {@code CHALLENGE} attempt.
     *
     * @param solved                whether the attempt ended {@code SOLVED} (a wrong or
     *                              abandoned attempt is {@link #INCORRECT} regardless of
     *                              anything else)
     * @param submissionCount       how many times Run was pressed in this attempt
     * @param hintsTaken            hint-ladder rungs climbed
     * @param failingCaseRevealed   whether the failing case was revealed
     * @param complexityClaimCorrect whether the self-reported complexity matched
     *                              measurement - {@code null} when the exercise carries
     *                              no complexity check or measurement was inconclusive,
     *                              which is treated as neutral, never as a contradiction
     *                              (the same honesty principle issue #17 established: an
     *                              inconclusive measurement is never worded as a failure)
     */
    public static int derive(
            boolean solved,
            int submissionCount,
            int hintsTaken,
            boolean failingCaseRevealed,
            Boolean complexityClaimCorrect) {
        if (!solved) {
            return INCORRECT;
        }
        boolean firstTryNoHelp = submissionCount <= 1 && hintsTaken == 0 && !failingCaseRevealed;
        boolean complexityNotContradicted = !Boolean.FALSE.equals(complexityClaimCorrect);
        return firstTryNoHelp && complexityNotContradicted ? PERFECT : WEAK_PASS;
    }
}
