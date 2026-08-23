package com.sweprep.backend.scheduler;

/**
 * Reduces one completed rep review to the 0-5 SM-2 quality score the scheduler consumes
 * (issue #20, issue #8's decision that an attempt record "collapses to a 0-5 quality score").
 * Kept as its own small, directly testable unit rather than buried inside the scheduler
 * arithmetic, per the ticket's explicit ask.
 *
 * <p>Three already-recorded signals feed it: whether the rep was answered correctly,
 * whether the solver asked to see the check's explanation despite being right (issue
 * #51's confidence signal, {@link com.sweprep.backend.attempt.Attempt#explanationRequested()}),
 * and whether the reference solution was seen before this review ever passed (issue
 * #82's honesty signal, {@link com.sweprep.backend.attempt.Attempt#solutionSeen()}). A
 * correct answer where the explanation was requested is treated as weaker than one where
 * it was not - the acceptance criterion this class exists to satisfy - so it earns a
 * middling pass rather than a perfect one; a correct answer that needed the solution
 * seen first is weaker still, so the problem comes back soon. A wrong (or abandoned)
 * answer earns the bottom score regardless of anything else. Time taken is deliberately
 * not a parameter: the ticket is explicit that interruptions and thinking-before-typing
 * make duration noise, not signal.
 */
public final class ReviewQuality {

    /** A correct answer, with no explanation requested: the top SM-2 mark. */
    public static final int PERFECT = 5;

    /** A correct answer where the explanation was requested anyway: weaker, but still a pass. */
    public static final int CORRECT_BUT_UNSURE = 3;

    /**
     * A correct answer that only came after the reference solution was revealed
     * (issue #82): still technically a pass - a correct answer is a correct answer - but
     * weaker than either mark above, so the spacing scheduler brings the problem back
     * soon rather than treating it as retrieved from memory.
     */
    public static final int SOLUTION_SEEN = 2;

    /** A wrong or abandoned answer: the bottom mark, whatever else happened. */
    public static final int INCORRECT = 0;

    private ReviewQuality() {}

    /**
     * The 0-5 quality score for one review.
     *
     * @param correct              whether the rep was answered correctly
     * @param explanationRequested whether the solver asked for the explanation on this review
     *                             (meaningful only when {@code correct}; a wrong answer is
     *                             already shown its explanation automatically, issue #51, so
     *                             the flag carries no extra weakness there)
     * @param solutionSeen         whether the reference solution was revealed on this attempt
     *                             before it passed (issue #82). Takes precedence over {@code
     *                             explanationRequested} when both are true - seeing the answer
     *                             is more severe than asking why a correct one is correct.
     */
    public static int derive(boolean correct, boolean explanationRequested, boolean solutionSeen) {
        if (!correct) {
            return INCORRECT;
        }
        if (solutionSeen) {
            return SOLUTION_SEEN;
        }
        return explanationRequested ? CORRECT_BUT_UNSURE : PERFECT;
    }
}
