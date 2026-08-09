package com.sweprep.backend.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Proves the SM-2 {@link RepScheduler} directly against its own contract - the acceptance
 * criteria of issue #20 as executable behaviour, not just SM-2's arithmetic: due dates advance
 * on correct answers, shorten on a wrong one, and a correct answer where the explanation was
 * requested schedules weaker (sooner) than an equally correct answer where it was not.
 */
class Sm2SchedulerTest {

    private static final LocalDate DAY_0 = LocalDate.of(2026, 1, 1);

    private final Sm2Scheduler scheduler = new Sm2Scheduler();

    @Test
    void aNeverReviewedRepIsDueImmediately() {
        RepSchedule schedule = scheduler.schedule(List.of());

        assertThat(schedule.dueOn()).isNull();
        assertThat(schedule.isDueOn(DAY_0)).isTrue();
        assertThat(schedule.isDueOn(DAY_0.plusYears(1))).isTrue();
    }

    @Test
    void aCorrectAnswerAdvancesTheDueDateIntoTheFuture() {
        RepSchedule schedule = scheduler.schedule(List.of(new Review(DAY_0, ReviewQuality.PERFECT)));

        assertThat(schedule.dueOn()).isAfter(DAY_0);
        assertThat(schedule.isDueOn(DAY_0)).isFalse();
        assertThat(schedule.repetitions()).isEqualTo(1);
    }

    @Test
    void repeatedCorrectAnswersGrowTheIntervalFurtherEachTime() {
        RepSchedule afterOne = scheduler.schedule(List.of(new Review(DAY_0, ReviewQuality.PERFECT)));
        RepSchedule afterTwo = scheduler.schedule(List.of(
                new Review(DAY_0, ReviewQuality.PERFECT),
                new Review(DAY_0.plusDays(1), ReviewQuality.PERFECT)));

        assertThat(afterTwo.intervalDays()).isGreaterThan(afterOne.intervalDays());
        assertThat(afterTwo.repetitions()).isEqualTo(2);
    }

    @Test
    void aWrongAnswerShortensTheDueDateBackToTheFirstRung() {
        List<Review> longStreak = List.of(
                new Review(DAY_0, ReviewQuality.PERFECT),
                new Review(DAY_0.plusDays(1), ReviewQuality.PERFECT),
                new Review(DAY_0.plusDays(7), ReviewQuality.PERFECT));
        RepSchedule beforeMiss = scheduler.schedule(longStreak);
        assertThat(beforeMiss.intervalDays()).isGreaterThan(6); // an established streak earned a long gap

        LocalDate missDay = DAY_0.plusDays(20);
        List<Review> withMiss = concat(longStreak, new Review(missDay, ReviewQuality.INCORRECT));
        RepSchedule afterMiss = scheduler.schedule(withMiss);

        // Shortened all the way back to the first rung, not merely reduced.
        assertThat(afterMiss.repetitions()).isEqualTo(0);
        assertThat(afterMiss.intervalDays()).isEqualTo(1);
        assertThat(afterMiss.dueOn()).isEqualTo(missDay.plusDays(1));
        assertThat(afterMiss.dueOn()).isBefore(beforeMiss.dueOn());
    }

    @Test
    void aWrongAnswerStillAdvancesTheDueDateFromTodayEvenThoughItIsShortenedOverall() {
        // "Shorten" means shorter than an unbroken correct streak would have produced, not that
        // the rep is due in the past - a miss still schedules one day out, not zero.
        RepSchedule schedule = scheduler.schedule(List.of(new Review(DAY_0, ReviewQuality.INCORRECT)));

        assertThat(schedule.dueOn()).isEqualTo(DAY_0.plusDays(1));
    }

    @Test
    void aCorrectAnswerWhereTheExplanationWasRequestedSchedulesWeakerThanAConfidentOne() {
        // Two identical-length streaks, differing only in whether each correct answer asked for
        // the explanation. Both are "correct answers" and both advance - the acceptance
        // criterion is that the confident streak is trusted with a longer gap.
        List<Review> confident = streakOfQuality(ReviewQuality.PERFECT);
        List<Review> unsure = streakOfQuality(ReviewQuality.CORRECT_BUT_UNSURE);

        RepSchedule confidentSchedule = scheduler.schedule(confident);
        RepSchedule unsureSchedule = scheduler.schedule(unsure);

        // Both still advance on every correct answer - neither is treated as wrong.
        assertThat(confidentSchedule.repetitions()).isEqualTo(unsureSchedule.repetitions());
        // But the "asked why" streak is scheduled back sooner: a weaker, less trusted gap.
        assertThat(unsureSchedule.easinessFactor()).isLessThan(confidentSchedule.easinessFactor());
        assertThat(unsureSchedule.dueOn()).isBefore(confidentSchedule.dueOn());
    }

    private static List<Review> streakOfQuality(int quality) {
        return List.of(
                new Review(DAY_0, quality),
                new Review(DAY_0.plusDays(1), quality),
                new Review(DAY_0.plusDays(7), quality));
    }

    private static List<Review> concat(List<Review> reviews, Review extra) {
        List<Review> combined = new java.util.ArrayList<>(reviews);
        combined.add(extra);
        return combined;
    }
}
