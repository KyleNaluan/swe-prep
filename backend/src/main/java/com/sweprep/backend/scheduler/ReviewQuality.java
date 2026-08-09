package com.sweprep.backend.scheduler;

/**
 * Reduces one completed rep review to the 0-5 SM-2 quality score the scheduler consumes
 * (issue #20, issue #8's decision that an attempt record "collapses to a 0-5 quality score").
 * Kept as its own small, directly testable unit rather than buried inside the scheduler
 * arithmetic, per the ticket's explicit ask.
 *
 * <p>Two already-recorded signals feed it, neither new: whether the rep was answered
 * correctly, and whether the solver asked to see the check's explanation despite being right
 * (issue #51's confidence signal, {@link com.sweprep.backend.attempt.Attempt#explanationRequested()}).
 * A correct answer where the explanation was requested is treated as weaker than one where it
 * was not - the acceptance criterion this class exists to satisfy - so it earns a middling
 * pass rather than a perfect one; a wrong (or abandoned) answer earns the bottom score
 * regardless of anything else. Time taken is deliberately not a parameter: the ticket is
 * explicit that interruptions and thinking-before-typing make duration noise, not signal.
 */
public final class ReviewQuality {

    /** A correct answer, with no explanation requested: the top SM-2 mark. */
    public static final int PERFECT = 5;

    /** A correct answer where the explanation was requested anyway: weaker, but still a pass. */
    public static final int CORRECT_BUT_UNSURE = 3;

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
     */
    public static int derive(boolean correct, boolean explanationRequested) {
        if (!correct) {
            return INCORRECT;
        }
        return explanationRequested ? CORRECT_BUT_UNSURE : PERFECT;
    }
}
