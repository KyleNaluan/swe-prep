package com.sweprep.backend.learned;

import com.sweprep.backend.attempt.CurrentUser;
import com.sweprep.backend.attempt.SubmissionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Wires the persistence and clock into the pure {@link LearnedCriterion} (issue #38): it
 * reads a user's clean machine-verdict passes, folds each into the calendar day it fell on,
 * and hands the day list to the criterion. Like {@link
 * com.sweprep.backend.reps.ConfusionPairService}, this is only the wiring - the rules live in
 * the pure class so they can be unit-tested without a database.
 *
 * <p>The day boundary is the injected {@link Clock}'s zone - the same {@code Clock} bean the
 * session loop reads "today" from (issue #19, {@code SessionConfig}) - so a "spaced session"
 * means the same calendar day everywhere in the app. That is the deliberate answer to what a
 * session is: not a sitting and not a {@code day_completion} record, but a distinct calendar
 * day, which is what lets the criterion survive a user who practises twice in one day (both
 * passes collapse to one session and cannot stack toward graduation).
 *
 * <p>This service supplies the "learned" state the scheduler (issue #8) will read; it does not
 * decide what to practise next - that is the scheduler's job, out of scope here.
 */
@Service
public class LearnedService {

    private final SubmissionRepository submissions;
    private final CurrentUser currentUser;
    private final LearnedProperties properties;
    private final ZoneId zone;

    public LearnedService(
            SubmissionRepository submissions,
            CurrentUser currentUser,
            LearnedProperties properties,
            Clock clock) {
        this.submissions = submissions;
        this.currentUser = currentUser;
        this.properties = properties;
        this.zone = clock.getZone();
    }

    /** The current user's successive-relearning state for one exercise. */
    public LearnedState stateFor(String exerciseId) {
        return stateFor(currentUser.id(), exerciseId);
    }

    /** A given user's successive-relearning state for one exercise. */
    public LearnedState stateFor(UUID userId, String exerciseId) {
        List<LocalDate> sessions = toSessionDays(submissions.cleanPassInstants(userId, exerciseId));
        return LearnedCriterion.evaluate(sessions, properties);
    }

    /**
     * Every exercise the current user has ever cleanly passed, mapped to its learned state,
     * in one query - the batch the scheduler evaluates the catalog with. Exercises the user
     * has never passed are absent (they are {@code NEW} and need no computation).
     */
    public Map<String, LearnedState> statesForAll() {
        return statesForAll(currentUser.id());
    }

    /** A given user's learned state for every exercise they have ever cleanly passed. */
    public Map<String, LearnedState> statesForAll(UUID userId) {
        return submissions.cleanPassInstantsByExercise(userId).entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> LearnedCriterion.evaluate(toSessionDays(entry.getValue()), properties)));
    }

    private List<LocalDate> toSessionDays(List<Instant> passes) {
        return passes.stream().map(instant -> LocalDate.ofInstant(instant, zone)).toList();
    }
}
