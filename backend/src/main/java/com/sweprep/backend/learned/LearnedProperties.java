package com.sweprep.backend.learned;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The successive-relearning criterion the scheduler (issue #8) reads to decide what is
 * "learned" (issue #38, design revision t3 section 4.1). Mastery is <em>not</em> an SM-2
 * ease number: an item is learned once it has been retrieved <em>to criterion</em> - one
 * clean machine-verdict pass - in each of {@link #n} distinct spaced sessions, across an
 * expanding {@link #gapLadder} of inter-session gaps.
 *
 * <p>Both knobs are deliberately configurable so the criterion can be tuned without a code
 * change, and so the acceptance test can vary them and watch the graduation outcome move:
 *
 * <ul>
 *   <li>{@code n} - how many spaced sessions a clean pass is required in. Recommended 3
 *       (Rawson &amp; Dunlosky 2022: spaced relearning beats single-session overlearning).
 *   <li>{@code gapLadder} - the minimum gap, in days, that must separate each successive
 *       counted session from the one before it. The gaps expand (Cepeda 2006: roughly
 *       10-20% of the target retention interval), which is why grinding an item repeatedly
 *       in one sitting can never graduate it - only calendar spacing advances the count.
 *       To advance from {@code k} counted sessions to {@code k+1}, the next clean pass must
 *       be at least {@code gapLadder[k-1]} days after the last counted session; the entry
 *       is clamped to the last rung when the ladder is shorter than {@code n} needs, so
 *       {@code n} and the ladder length are independently configurable and never crash.
 * </ul>
 *
 * <p>The learned state is <em>derived</em> from the merged {@code V3} attempt/submission
 * rows, never stored, so this criterion costs no migration (design revision t3 section 5).
 *
 * @param n         spaced sessions a clean pass is required in; defaults to 3, floored at 1
 * @param gapLadder minimum day-gaps between successive counted sessions, expanding;
 *                  defaults to {@code [1, 3, 7, 14]}, each entry floored at 1
 */
@ConfigurationProperties(prefix = "sweprep.learned")
public record LearnedProperties(Integer n, List<Integer> gapLadder) {

    private static final List<Integer> DEFAULT_LADDER = List.of(1, 3, 7, 14);

    public LearnedProperties {
        n = (n == null || n < 1) ? 3 : n;
        List<Integer> ladder = (gapLadder == null || gapLadder.isEmpty()) ? DEFAULT_LADDER : gapLadder;
        // A gap below one day would let two passes in the same or adjacent sitting stack,
        // which is exactly the single-session grinding the criterion rejects; floor at one.
        gapLadder = ladder.stream().map(gap -> gap == null || gap < 1 ? 1 : gap).toList();
    }

    /**
     * The minimum gap, in days, required to advance from {@code counted} spaced sessions to
     * the next one - {@code gapLadder[counted-1]}, clamped to the last rung when the ladder
     * is shorter than the criterion needs. {@code counted} is the number of sessions already
     * counted (at least 1 when a gap is being asked for).
     */
    public int gapAfter(int counted) {
        int index = Math.max(0, Math.min(counted - 1, gapLadder.size() - 1));
        return gapLadder.get(index);
    }
}
