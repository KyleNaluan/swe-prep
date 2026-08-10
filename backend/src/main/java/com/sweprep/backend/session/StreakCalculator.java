package com.sweprep.backend.session;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Set;

/**
 * The capped repair mechanic (issue #22, decision issue #7 item 5), pure and unit-tested
 * directly with no database - the same "pure rules, thin wiring" split {@link
 * com.sweprep.backend.learned.LearnedCriterion} and {@link
 * com.sweprep.backend.reps.ConfusionPairs} use.
 *
 * <p>A "double session" is the required warm-up plus a solved {@code CHALLENGE} on the
 * same calendar day - the existing three-tier model (issue #19) already gives this a
 * real meaning, so no new "session count" concept was invented. Walking backward from
 * today, a missed day is bridged (the streak run continues through it) exactly when the
 * day right after it - which the walk has therefore already counted - was both a
 * completed day <em>and</em> a solved-challenge day. The missed day itself is never
 * counted as practised; only the run's continuity survives it. Nothing is ever written
 * to record a repair: like {@link com.sweprep.backend.learned.LearnedService}, this is
 * fully re-derived from {@code day_completion} and solved-{@code CHALLENGE} attempts on
 * every read, so a repair used long ago costs no migration and cannot drift from the
 * real history.
 *
 * <p>The monthly cap ({@link StreakProperties#maxRepairsPerMonth()}) only restricts
 * repairs whose missed day falls in {@code today}'s calendar month - a repair from an
 * earlier month is already "spent" history and is never retroactively invalidated by
 * this read; the cap only ever binds going forward, on this month's remaining budget.
 */
public final class StreakCalculator {

    private StreakCalculator() {}

    /**
     * @param completedDays       every calendar day the warm-up was completed
     * @param challengeSolvedDays every calendar day a {@code CHALLENGE} was solved -
     *                            the "double session" half of a repair
     * @param today               the current calendar day, in the app's clock zone
     * @param todayComplete       whether today's warm-up is already done (mirrors {@code
     *                            SessionService#streak}'s existing "count from yesterday
     *                            when today is not done yet" rule)
     */
    public static StreakResult evaluate(
            Set<LocalDate> completedDays,
            Set<LocalDate> challengeSolvedDays,
            LocalDate today,
            boolean todayComplete,
            StreakProperties properties) {
        YearMonth currentMonth = YearMonth.from(today);
        int streak = 0;
        int repairsUsedThisMonth = 0;

        LocalDate cursor = todayComplete ? today : today.minusDays(1);
        while (true) {
            if (completedDays.contains(cursor)) {
                streak++;
                cursor = cursor.minusDays(1);
                continue;
            }
            // cursor is a missed day. It is repairable exactly when the day right after
            // it - already walked and, if completed, already counted above - was both
            // completed and a solved-challenge day: the double session that earns the
            // repair.
            LocalDate repairDay = cursor.plusDays(1);
            boolean doubleSessionOnRepairDay =
                    completedDays.contains(repairDay) && challengeSolvedDays.contains(repairDay);
            boolean missedDayInCurrentMonth = YearMonth.from(cursor).equals(currentMonth);
            boolean capAvailable = !missedDayInCurrentMonth || repairsUsedThisMonth < properties.maxRepairsPerMonth();

            if (doubleSessionOnRepairDay && capAvailable) {
                if (missedDayInCurrentMonth) {
                    repairsUsedThisMonth++;
                }
                cursor = cursor.minusDays(1);
                continue;
            }
            break;
        }

        int remaining = Math.max(0, properties.maxRepairsPerMonth() - repairsUsedThisMonth);
        boolean repairPending = !completedDays.contains(today.minusDays(1))
                && completedDays.contains(today.minusDays(2))
                && !challengeSolvedDays.contains(today)
                && repairsUsedThisMonth < properties.maxRepairsPerMonth();

        return new StreakResult(streak, repairsUsedThisMonth, remaining, repairPending);
    }
}
