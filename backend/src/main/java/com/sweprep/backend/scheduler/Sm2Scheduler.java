package com.sweprep.backend.scheduler;

import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * SM-2 (Wozniak 1990), the first {@link RepScheduler} (issue #20). About fifty lines is
 * entirely adequate for one user and a few hundred reps - reaching for FSRS or a configurable
 * rule engine here would be solving a problem this app does not have. Swap the {@code
 * RepScheduler} bean, not this class's internals, if that ever changes.
 *
 * <p>Each review updates the easiness factor by the standard SM-2 delta and, on a quality of 3
 * or better, grows the interval (1 day, then 6, then the previous interval times easiness) and
 * advances the repetition count - the "advance on correct answers" acceptance criterion. A
 * quality below 3 resets the repetition count and interval back to the first rung - "shorten
 * on wrong answers". A quality of 3 ({@link ReviewQuality#CORRECT_BUT_UNSURE}, a correct answer
 * where the explanation was requested) still advances - a correct answer is a correct answer -
 * but earns a smaller, possibly negative, easiness delta than a quality of 5, so its interval
 * grows slower from the next review on: the "weaker" acceptance criterion, expressed as a
 * diverging trajectory rather than a one-off penalty.
 */
@Component
public class Sm2Scheduler implements RepScheduler {

    private static final double INITIAL_EASINESS = 2.5;
    private static final double MIN_EASINESS = 1.3;

    @Override
    public RepSchedule schedule(List<Review> reviews) {
        double easiness = INITIAL_EASINESS;
        int repetitions = 0;
        int interval = 0;
        LocalDate lastReviewedOn = null;

        for (Review review : reviews) {
            int quality = review.quality();
            if (quality >= 3) {
                interval = nextInterval(repetitions, interval, easiness);
                repetitions++;
            } else {
                repetitions = 0;
                interval = 1;
            }
            easiness = Math.max(MIN_EASINESS, easiness + easinessDelta(quality));
            lastReviewedOn = review.reviewedOn();
        }

        LocalDate dueOn = lastReviewedOn == null ? null : lastReviewedOn.plusDays(interval);
        return new RepSchedule(dueOn, interval, easiness, repetitions, lastReviewedOn);
    }

    /** The standard SM-2 interval ladder: 1 day, then 6, then previous interval times easiness. */
    private static int nextInterval(int repetitionsSoFar, int previousInterval, double easiness) {
        if (repetitionsSoFar == 0) {
            return 1;
        }
        if (repetitionsSoFar == 1) {
            return 6;
        }
        return (int) Math.round(previousInterval * easiness);
    }

    /** The standard SM-2 easiness update: quality 5 grows it, 4 leaves it unchanged, below that shrinks it. */
    private static double easinessDelta(int quality) {
        double gap = 5 - quality;
        return 0.1 - gap * (0.08 + gap * 0.02);
    }
}
