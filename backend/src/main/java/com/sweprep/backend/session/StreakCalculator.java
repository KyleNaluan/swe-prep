package com.sweprep.backend.session;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * A per-day projection of the same completed/challenge-solved history {@link
     * #evaluate} reads, for the Direction C graft's day ribbon (last 30 days) and the
     * Direction A graft's year-record grid (Readiness) - one derivation serving both,
     * per the report's recommendation, since both are just different window lengths
     * over {@code day_completion}.
     *
     * <p>Walks backward from {@code today} exactly like {@link #evaluate}, classifying
     * every day in the window - not stopping at the first unrepaired gap the way {@link
     * #evaluate} does, since a picture of the record must show every day, broken streak
     * or not. {@code bridged} uses the identical local rule {@link #evaluate} uses (the
     * day right after a miss was both completed and a solved-challenge day), but applies
     * the monthly cap independently per calendar month the missed day actually fell in -
     * a deliberate generalisation of {@link #evaluate}'s "only this month is capped"
     * shortcut (safe there because past months are already-spent history it never
     * revisits), so a past month never shows more bridged gaps in the picture than its
     * own budget ever allowed.
     *
     * @param windowDays how many trailing days to return, including today
     */
    public static List<DayHistory> history(
            Set<LocalDate> completedDays,
            Set<LocalDate> challengeSolvedDays,
            LocalDate today,
            int windowDays,
            StreakProperties properties) {
        List<DayHistory> out = new ArrayList<>();
        Map<YearMonth, Integer> repairsUsedByMonth = new HashMap<>();
        LocalDate start = today.minusDays(windowDays - 1L);

        for (LocalDate cursor = today; !cursor.isBefore(start); cursor = cursor.minusDays(1)) {
            boolean completed = completedDays.contains(cursor);
            boolean doubleSession = completed && challengeSolvedDays.contains(cursor);
            boolean bridged = false;
            if (!completed) {
                LocalDate repairDay = cursor.plusDays(1);
                boolean doubleSessionOnRepairDay =
                        completedDays.contains(repairDay) && challengeSolvedDays.contains(repairDay);
                YearMonth month = YearMonth.from(cursor);
                int used = repairsUsedByMonth.getOrDefault(month, 0);
                if (doubleSessionOnRepairDay && used < properties.maxRepairsPerMonth()) {
                    bridged = true;
                    repairsUsedByMonth.merge(month, 1, Integer::sum);
                }
            }
            out.add(new DayHistory(cursor, completed, doubleSession, bridged));
        }
        Collections.reverse(out); // oldest first, the order a ribbon/grid renders left-to-right
        return out;
    }
}
