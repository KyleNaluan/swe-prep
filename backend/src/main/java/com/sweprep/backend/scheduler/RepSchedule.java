package com.sweprep.backend.scheduler;

import java.time.LocalDate;

/**
 * The current spaced-repetition state of one rep for one user: how many consecutive correct
 * reviews it has survived, the algorithm's per-item confidence, and when it is next due.
 * Computed by a {@link RepScheduler} from the rep's review history; never persisted (issue
 * #20) - like {@link com.sweprep.backend.learned.LearnedState}, it is a pure derivation
 * recomputed on read from the attempt history, not a mutable row a write path has to keep
 * in sync with every submission and explanation request.
 *
 * @param dueOn           the day the next review is due, or {@code null} for a rep never
 *                        reviewed - due immediately, with no gap to wait out
 * @param intervalDays    the gap, in days, the algorithm most recently earned this rep
 * @param easinessFactor  the algorithm's per-item confidence multiplier
 * @param repetitions     consecutive correct reviews counted so far, reset by a wrong one
 * @param lastReviewedOn  the day of the most recent review, or {@code null} if never reviewed
 */
public record RepSchedule(
        LocalDate dueOn,
        int intervalDays,
        double easinessFactor,
        int repetitions,
        LocalDate lastReviewedOn) {

    /** Whether this rep is due on {@code today}: never reviewed, or its due date has arrived. */
    public boolean isDueOn(LocalDate today) {
        return dueOn == null || !dueOn.isAfter(today);
    }
}
