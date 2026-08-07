package com.sweprep.backend.learned;

import java.time.LocalDate;
import java.util.Optional;

/**
 * The derived successive-relearning state of one exercise for one user (issue #38): how
 * far it has progressed toward being "learned", and when it is next due, expressed in the
 * criterion's own terms (spaced sessions and expanding gaps) rather than an SM-2 ease
 * number. Computed by {@link LearnedCriterion} from clean machine-verdict passes; never
 * stored.
 *
 * @param status            where the item sits: never passed, in progress, or learned
 * @param spacedSessions    clean passes counted in distinct, properly spaced sessions so
 *                          far (capped at {@link #criterion}); repeated passes within one
 *                          session count once
 * @param criterion         the configured {@code n}: spaced sessions required to graduate
 * @param lastSpacedSession the day of the most recent counted session, or {@code null}
 *                          when none has been counted yet
 * @param nextGapDays       the minimum gap, in days, before another clean pass can advance
 *                          the item - the next expanding rung while learning, or the
 *                          relearning interval once learned. {@code 0} for a never-seen
 *                          item (available cold).
 */
public record LearnedState(
        Status status,
        int spacedSessions,
        int criterion,
        LocalDate lastSpacedSession,
        int nextGapDays) {

    /** Whether an item has ever been retrieved, and whether it has reached the criterion. */
    public enum Status {
        /** Never retrieved to criterion - no clean machine-verdict pass on record. */
        NEW,
        /** At least one spaced pass, but fewer than the criterion requires. */
        LEARNING,
        /** A clean pass in each of {@code n} spaced sessions: the criterion is met. */
        LEARNED
    }

    public boolean isLearned() {
        return status == Status.LEARNED;
    }

    /**
     * The earliest day a clean pass would count as the next spaced session (learning) or
     * relearning review (learned) - {@link #lastSpacedSession} plus {@link #nextGapDays}.
     * Empty for a {@code NEW} item, which is due cold with no gap to wait out.
     */
    public Optional<LocalDate> nextReviewOn() {
        return lastSpacedSession == null
                ? Optional.empty()
                : Optional.of(lastSpacedSession.plusDays(nextGapDays));
    }
}
