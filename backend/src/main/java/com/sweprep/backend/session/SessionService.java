package com.sweprep.backend.session;

import com.sweprep.backend.attempt.AttemptRepository;
import com.sweprep.backend.attempt.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns "done for today" - the durable spine of the daily session loop (issue #19).
 *
 * <p>The session has three tiers but only the first is required: completing the ~8-rep
 * warm-up {@linkplain #completeWarmup() marks the day complete}, and that alone is the
 * whole obligation. The optional main exercise and the open continuation that follow are
 * bonus; whether they happen, are declined, or are abandoned part-way, the day stays
 * complete. So this service records exactly one thing - that the warm-up was finished -
 * and never consults attempts to decide it. The point of the small required core is that
 * a bad, low-motivation day is still a day you finish; nothing here may make declining
 * the main exercise feel like failing the day.
 *
 * <p>{@link #status()} is read on every app open to show the streak, so it stays a cheap
 * pair of indexed reads - it must never sit between the user and their first rep.
 */
@Service
public class SessionService {

    private final DayCompletionRepository days;
    private final AttemptRepository attempts;
    private final CurrentUser currentUser;
    private final Clock clock;
    private final StreakProperties streakProperties;

    public SessionService(
            DayCompletionRepository days,
            AttemptRepository attempts,
            CurrentUser currentUser,
            Clock clock,
            StreakProperties streakProperties) {
        this.days = days;
        this.attempts = attempts;
        this.currentUser = currentUser;
        this.clock = clock;
        this.streakProperties = streakProperties;
    }

    /**
     * Marks today complete because the warm-up is finished, and returns the fresh
     * status. Idempotent: finishing a second warm-up on the same day changes nothing.
     * This is the only path that completes a day - the required core, and nothing else.
     */
    @Transactional
    public SessionStatus completeWarmup() {
        days.markComplete(currentUser.id(), today(), clock.instant());
        return status();
    }

    /**
     * Today's session status: whether the day is done, when, the current streak (a
     * repaired gap does not break it - issue #22), and the repair ledger for this
     * calendar month.
     */
    public SessionStatus status() {
        UUID user = currentUser.id();
        LocalDate today = today();
        Set<LocalDate> completed = new HashSet<>(days.completedDates(user));
        Set<LocalDate> challengeSolved = challengeSolvedDates(user);

        boolean dayComplete = completed.contains(today);
        Instant completedAt = dayComplete
                ? days.find(user, today).map(DayCompletion::completedAt).orElse(null)
                : null;
        StreakResult streak =
                StreakCalculator.evaluate(completed, challengeSolved, today, dayComplete, streakProperties);
        return new SessionStatus(
                dayComplete,
                completedAt,
                streak.streak(),
                streak.repairsRemainingThisMonth(),
                streak.repairPending());
    }

    /**
     * The day ribbon's and year-record grid's shared source (issue #90's Direction A/C
     * graft): {@link StreakCalculator#history} projected over the last {@link
     * #HISTORY_WINDOW_DAYS} days, oldest first. One endpoint, two views - the ribbon
     * slices the trailing 30, the year grid renders the whole window.
     */
    public List<DayHistory> history() {
        UUID user = currentUser.id();
        LocalDate today = today();
        Set<LocalDate> completed = new HashSet<>(days.completedDates(user));
        Set<LocalDate> challengeSolved = challengeSolvedDates(user);
        return StreakCalculator.history(completed, challengeSolved, today, HISTORY_WINDOW_DAYS, streakProperties);
    }

    private Set<LocalDate> challengeSolvedDates(UUID user) {
        return attempts.challengeSolvedInstants(user).stream()
                .map(instant -> LocalDate.ofInstant(instant, clock.getZone()))
                .collect(Collectors.toSet());
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    // Long enough to cover Direction A's year-record grid (182 days); the ribbon is
    // just this same response's trailing 30 days, sliced client-side.
    private static final int HISTORY_WINDOW_DAYS = 182;
}
