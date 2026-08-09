package com.sweprep.backend.scheduler;

import java.util.List;

/**
 * The swappable spaced-repetition contract (issue #20, issue #8's decision - "a due-date SRS
 * queue for Reps"). Turns a rep's ordered review history into its current {@link RepSchedule};
 * {@link Sm2Scheduler} is the <em>first</em> implementation, not the contract. Nothing outside
 * this package - not the warm-up selector, not the session flow - may know which algorithm is
 * in use; a future replacement (FSRS, or anything else) is a swap of the bean behind this
 * interface, never a change anywhere scheduling is consumed.
 */
public interface RepScheduler {

    /**
     * The schedule implied by a rep's review history, oldest first. An empty history means the
     * rep has never been reviewed - due immediately (see {@link RepSchedule#isDueOn}), the
     * cold-start answer.
     */
    RepSchedule schedule(List<Review> reviews);
}
