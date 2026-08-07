package com.sweprep.backend.learned;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/**
 * Computes whether an exercise is "learned" from the days on which it was retrieved to
 * criterion (issue #38, design revision t3 section 4.1). This is the definition the
 * scheduler (issue #8) was missing: successive relearning, not an SM-2 ease threshold.
 *
 * <p>The input is the set of <em>sessions</em> in which a clean pass occurred. A session is
 * a distinct calendar day (in the app's clock zone - the same day boundary the session loop
 * uses, issue #19), which is what makes the criterion survive a user who practises twice in
 * one day: two passes on the same day are one session, so <b>repeated passes within a single
 * sitting never stack toward graduation</b> - exactly the single-session grinding the ruling
 * rejects (Rawson &amp; Dunlosky 2022). Only calendar spacing across the expanding gap ladder
 * advances the count.
 *
 * <p>The class is pure and immutable, like {@link com.sweprep.backend.reps.ConfusionPairs}:
 * it takes the pass-days and the criterion and returns the state with no database, so its
 * rules - including graduation, the greedy spacing walk, and the relearning interval - are
 * unit-testable without one. What it deliberately never takes is a self-check self-rating or
 * a lesson read: the objective competence signal is built from clean machine verdicts alone.
 * The clean passes come from
 * {@link com.sweprep.backend.attempt.SubmissionRepository#cleanPassInstants}, whose
 * {@code outcome = 'PASSED'} filter no self-check or read can satisfy, so a self-rating or a
 * read can never reach this computation.
 */
public final class LearnedCriterion {

    private LearnedCriterion() {}

    /**
     * The learned state implied by the days a clean pass occurred on. The list may contain
     * duplicates and any order (one entry per passing submission); it is reduced to distinct
     * days, sorted ascending, then walked greedily: the first pass-day is the first counted
     * session, and each later day counts as the next session only if it is at least
     * {@link LearnedProperties#gapAfter} days past the last counted one. A day that arrives
     * too soon simply does not advance the count - it neither stacks nor resets - so the walk
     * finds the earliest subsequence of sessions that honours the expanding gaps, which is
     * the soonest an item can graduate.
     *
     * @param cleanPassSessions the day of each clean machine-verdict pass (duplicates and
     *                          out-of-order entries allowed; {@code null} entries ignored)
     * @param props             the configured criterion ({@code n} and the gap ladder)
     * @return the derived {@link LearnedState}
     */
    public static LearnedState evaluate(List<LocalDate> cleanPassSessions, LearnedProperties props) {
        List<LocalDate> distinct = cleanPassSessions.stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();

        if (distinct.isEmpty()) {
            // Never retrieved: available cold, no gap to wait out.
            return new LearnedState(LearnedState.Status.NEW, 0, props.n(), null, 0);
        }

        int counted = 1;
        LocalDate lastCounted = distinct.get(0);
        for (int i = 1; i < distinct.size() && counted < props.n(); i++) {
            LocalDate day = distinct.get(i);
            long gap = ChronoUnit.DAYS.between(lastCounted, day);
            if (gap >= props.gapAfter(counted)) {
                counted++;
                lastCounted = day;
            }
        }

        LearnedState.Status status =
                counted >= props.n() ? LearnedState.Status.LEARNED : LearnedState.Status.LEARNING;
        // The next gap the scheduler must respect: the rung to reach the following session
        // while learning, or the relearning interval once the criterion is met.
        int nextGapDays = props.gapAfter(counted);
        return new LearnedState(status, counted, props.n(), lastCounted, nextGapDays);
    }
}
