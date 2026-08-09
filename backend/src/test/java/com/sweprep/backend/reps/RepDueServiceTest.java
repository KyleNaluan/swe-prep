package com.sweprep.backend.reps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sweprep.backend.attempt.AttemptRepository;
import com.sweprep.backend.attempt.AttemptRepository.RepReview;
import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.Form;
import com.sweprep.backend.scheduler.Sm2Scheduler;
import com.sweprep.backend.testsupport.Fixtures;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Proves the wiring the pure {@link Sm2Scheduler} cannot: that {@link RepDueService} reduces
 * raw attempt rows to review history correctly, including the same-calendar-day collapsing
 * that keeps a re-queued rep from grinding extra spaced credit out of one warm-up sitting
 * (mirroring {@link com.sweprep.backend.learned.LearnedCriterion}'s distinct-session rule).
 */
class RepDueServiceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final Instant DAY_0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void aRepNeverReviewedIsDueImmediately() {
        AttemptRepository attempts = mock(AttemptRepository.class);
        when(attempts.repReviews(USER)).thenReturn(List.of());
        RepDueService service = service(attempts, DAY_0);

        assertThat(service.dueToday(USER, List.of(Fixtures.patternIdRep())))
                .contains(Fixtures.patternIdRep().id());
    }

    @Test
    void aFreshlyCorrectRepIsNotDueTheSameDay() {
        AttemptRepository attempts = mock(AttemptRepository.class);
        when(attempts.repReviews(USER))
                .thenReturn(List.of(new RepReview(Fixtures.patternIdRep().id(), DAY_0, true, false)));
        RepDueService service = service(attempts, DAY_0);

        assertThat(service.dueToday(USER, List.of(Fixtures.patternIdRep())))
                .doesNotContain(Fixtures.patternIdRep().id());
    }

    @Test
    void aWrongAnswerYesterdayIsDueAgainToday() {
        AttemptRepository attempts = mock(AttemptRepository.class);
        when(attempts.repReviews(USER))
                .thenReturn(List.of(new RepReview(Fixtures.patternIdRep().id(), DAY_0, false, false)));
        RepDueService service = service(attempts, DAY_0.plus(java.time.Duration.ofDays(1)));

        assertThat(service.dueToday(USER, List.of(Fixtures.patternIdRep())))
                .contains(Fixtures.patternIdRep().id());
    }

    @Test
    void multipleReviewsOnOneCalendarDayCollapseToOneUsingTheLastOutcome() {
        // Two SOLVED attempts on the same rep, same day - the requeue-and-retry shape a
        // warm-up sitting produces. If these were counted as two distinct spaced reviews the
        // second would jump the interval straight to six days; collapsed to one, the interval
        // stays at its first rung (one day), so the rep is due again the very next day.
        String id = Fixtures.patternIdRep().id();
        AttemptRepository attempts = mock(AttemptRepository.class);
        when(attempts.repReviews(USER)).thenReturn(List.of(
                new RepReview(id, DAY_0, true, false),
                new RepReview(id, DAY_0.plus(java.time.Duration.ofHours(1)), true, false)));

        RepDueService service = service(attempts, DAY_0.plus(java.time.Duration.ofDays(1)));

        assertThat(service.dueToday(USER, List.of(Fixtures.patternIdRep()))).contains(id);
    }

    @Test
    void onlyRepFormExercisesAreEverReportedDue() {
        Exercise challenge = com.sweprep.backend.testsupport.Fixtures.pairInAnyOrder();
        assertThat(challenge.form()).isEqualTo(Form.CHALLENGE);
        AttemptRepository attempts = mock(AttemptRepository.class);
        when(attempts.repReviews(USER)).thenReturn(List.of());
        RepDueService service = service(attempts, DAY_0);

        assertThat(service.dueToday(USER, List.of(challenge))).isEmpty();
    }

    private static RepDueService service(AttemptRepository attempts, Instant now) {
        return new RepDueService(attempts, new Sm2Scheduler(), Clock.fixed(now, ZoneOffset.UTC));
    }
}
