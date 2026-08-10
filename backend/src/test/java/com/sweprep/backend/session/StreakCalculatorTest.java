package com.sweprep.backend.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The capped repair mechanic (issue #22, decision issue #7 item 5), tested with fixed
 * dates and no database - the same discipline {@link
 * com.sweprep.backend.learned.LearnedCriterionTest} uses for the learned criterion.
 */
class StreakCalculatorTest {

    // A Wednesday, deliberately mid-month so a fixed handful of days back never crosses
    // a calendar-month boundary by accident in a test that is not testing that boundary.
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 12);

    private static final StreakProperties DEFAULT_CAP = new StreakProperties(null); // 2/month

    private static LocalDate day(int offsetFromToday) {
        return TODAY.plusDays(offsetFromToday);
    }

    @Test
    void consecutiveCompletedDaysCountNormallyWithNoGap() {
        Set<LocalDate> completed = Set.of(day(0), day(-1), day(-2));

        StreakResult result = StreakCalculator.evaluate(completed, Set.of(), TODAY, true, DEFAULT_CAP);

        assertThat(result.streak()).isEqualTo(3);
        assertThat(result.repairsUsedThisMonth()).isZero();
    }

    @Test
    void anUnrepairedGapBreaksTheStreak() {
        // Day -2 is complete, day -1 is missed (no challenge solved on day 0 either).
        Set<LocalDate> completed = Set.of(day(0), day(-2));

        StreakResult result = StreakCalculator.evaluate(completed, Set.of(), TODAY, true, DEFAULT_CAP);

        assertThat(result.streak()).isEqualTo(1); // only today counts
    }

    @Test
    void aGapRepairedByADoubleSessionTheDayAfterKeepsTheStreakContinuous() {
        // Miss day -1; today is both completed and a solved challenge - the double
        // session that repairs it.
        Set<LocalDate> completed = Set.of(day(0), day(-2), day(-3));
        Set<LocalDate> challengeSolved = Set.of(day(0));

        StreakResult result = StreakCalculator.evaluate(completed, challengeSolved, TODAY, true, DEFAULT_CAP);

        // today + day(-2) + day(-3) = 3; the missed day(-1) itself is not counted.
        assertThat(result.streak()).isEqualTo(3);
        assertThat(result.repairsUsedThisMonth()).isEqualTo(1);
        assertThat(result.repairsRemainingThisMonth()).isEqualTo(1);
    }

    @Test
    void aCompletedDayWithNoChallengeSolvedDoesNotRepair() {
        // Today is completed but no challenge was solved - not a double session.
        Set<LocalDate> completed = Set.of(day(0), day(-2), day(-3));

        StreakResult result = StreakCalculator.evaluate(completed, Set.of(), TODAY, true, DEFAULT_CAP);

        assertThat(result.streak()).isEqualTo(1);
    }

    @Test
    void theCapStopsAThirdRepairInTheSameMonth() {
        // Three candidate gaps (day -1, -3, -5), each immediately followed by a genuine
        // double session on the day after it (day 0, -2, -4 respectively) - all within
        // the same calendar month as TODAY. day -6 anchors the walk beyond the third gap.
        Set<LocalDate> completed = Set.of(day(0), day(-2), day(-4), day(-6));
        Set<LocalDate> challengeSolved = Set.of(day(0), day(-2), day(-4));

        StreakResult result = StreakCalculator.evaluate(completed, challengeSolved, TODAY, true, DEFAULT_CAP);

        // The cap (2) spends bridging day(-1) and day(-3); by the time the walk reaches
        // day(-5) the cap is exhausted, so that gap is not bridged and the walk stops
        // there - day(-6) is never reached even though it is completed.
        assertThat(result.repairsUsedThisMonth()).isEqualTo(2);
        assertThat(result.repairsRemainingThisMonth()).isZero();
        assertThat(result.streak()).isEqualTo(3); // today, day(-2), day(-4)
    }

    @Test
    void aRepairFromAnEarlierCalendarMonthDoesNotCountAgainstThisMonthsCap() {
        // One gap this month (day -1, bridged by today's double session), and a second,
        // independent gap at day -13 (2026-07-30, last month), bridged by a double
        // session at day -12 (2026-07-31). A contiguous completed run from day -2
        // through day -12 connects the two so the walk actually reaches July.
        Set<LocalDate> completed = new HashSet<>();
        completed.add(day(0));
        for (int i = 2; i <= 12; i++) {
            completed.add(day(-i));
        }
        Set<LocalDate> challengeSolved = Set.of(day(0), day(-12));

        StreakResult result = StreakCalculator.evaluate(completed, challengeSolved, TODAY, true, DEFAULT_CAP);

        // Both gaps are bridged (the streak survives both), but only the one whose
        // missed day (day -1) falls in the current calendar month is counted against
        // this month's cap - the July gap (day -13) leaves it untouched.
        assertThat(result.streak()).isEqualTo(12); // today + day(-2)..day(-12)
        assertThat(result.repairsUsedThisMonth()).isEqualTo(1);
        assertThat(result.repairsRemainingThisMonth()).isEqualTo(1);
    }

    @Test
    void repairPendingIsTrueWhenYesterdayWasMissedAndTheDayBeforeWasLive() {
        Set<LocalDate> completed = Set.of(day(-2)); // yesterday missed, day before live
        // Today's warm-up not done yet, no challenge solved today either.

        StreakResult result = StreakCalculator.evaluate(completed, Set.of(), TODAY, false, DEFAULT_CAP);

        assertThat(result.repairPending()).isTrue();
    }

    @Test
    void repairPendingIsFalseOnceTodaysChallengeIsAlreadySolved() {
        Set<LocalDate> completed = Set.of(day(-2));
        Set<LocalDate> challengeSolved = Set.of(day(0));

        StreakResult result = StreakCalculator.evaluate(completed, challengeSolved, TODAY, false, DEFAULT_CAP);

        assertThat(result.repairPending()).isFalse();
    }

    @Test
    void repairPendingIsFalseWhenThereWasNoLiveStreakToRepair() {
        // Yesterday missed, but the day before was not live either - nothing to repair.
        Set<LocalDate> completed = Set.of();

        StreakResult result = StreakCalculator.evaluate(completed, Set.of(), TODAY, false, DEFAULT_CAP);

        assertThat(result.repairPending()).isFalse();
    }

    @Test
    void neverPracticedIsAZeroStreakWithNoRepairsUsed() {
        StreakResult result = StreakCalculator.evaluate(Set.of(), Set.of(), TODAY, false, DEFAULT_CAP);

        assertThat(result.streak()).isZero();
        assertThat(result.repairsUsedThisMonth()).isZero();
        assertThat(result.repairsRemainingThisMonth()).isEqualTo(2);
    }
}
