package com.sweprep.backend.session;

/**
 * What {@link StreakCalculator#evaluate} derives: the live streak (already bridging any
 * repaired gaps) plus the repair ledger for the current calendar month, all computed on
 * read from {@code day_completion} and solved-{@code CHALLENGE} history - nothing here
 * is stored (issue #22).
 *
 * @param streak                    consecutive days practised, ending today (or
 *                                  yesterday if today is not yet complete) - a repaired
 *                                  gap does not break this count, but the missed day
 *                                  itself is never counted as an extra practised day
 * @param repairsUsedThisMonth      how many missed days within the current calendar
 *                                  month have already been bridged by a double session
 * @param repairsRemainingThisMonth {@link StreakProperties#maxRepairsPerMonth()} minus
 *                                  {@link #repairsUsedThisMonth}, floored at 0
 * @param repairPending             whether solving a challenge today (in addition to
 *                                  the required warm-up) would repair a currently
 *                                  broken streak right now - true only when yesterday
 *                                  was missed, the day before was live, the cap is not
 *                                  exhausted, and today has not already used the repair
 */
public record StreakResult(
        int streak, int repairsUsedThisMonth, int repairsRemainingThisMonth, boolean repairPending) {}
