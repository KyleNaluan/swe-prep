package com.sweprep.backend.session;

import com.sweprep.backend.attempt.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
    private final CurrentUser currentUser;
    private final Clock clock;

    public SessionService(DayCompletionRepository days, CurrentUser currentUser, Clock clock) {
        this.days = days;
        this.currentUser = currentUser;
        this.clock = clock;
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

    /** Today's session status: whether the day is done, when, and the current streak. */
    public SessionStatus status() {
        UUID user = currentUser.id();
        LocalDate today = today();
        List<LocalDate> completed = days.completedDates(user);
        Set<LocalDate> asSet = new HashSet<>(completed);

        boolean dayComplete = asSet.contains(today);
        Instant completedAt = dayComplete
                ? days.find(user, today).map(DayCompletion::completedAt).orElse(null)
                : null;
        return new SessionStatus(dayComplete, completedAt, streak(asSet, today, dayComplete));
    }

    // The run of consecutive completed days ending at the reference day. If today is not
    // yet complete we count from yesterday, so a streak earned through yesterday is still
    // shown as live before today's warm-up is done (and completing it then extends it),
    // rather than reading as zero until the day's rep is in.
    private int streak(Set<LocalDate> completed, LocalDate today, boolean dayComplete) {
        LocalDate cursor = dayComplete ? today : today.minusDays(1);
        int streak = 0;
        while (completed.contains(cursor)) {
            streak++;
            cursor = cursor.minusDays(1);
        }
        return streak;
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }
}
