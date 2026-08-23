package com.sweprep.backend.reps;

import com.sweprep.backend.attempt.AttemptRepository;
import com.sweprep.backend.attempt.AttemptRepository.RepReview;
import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.Form;
import com.sweprep.backend.scheduler.RepScheduler;
import com.sweprep.backend.scheduler.Review;
import com.sweprep.backend.scheduler.ReviewQuality;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Wires attempt history and the clock into the pure {@link RepScheduler} (issue #20), the same
 * shape as {@link com.sweprep.backend.learned.LearnedService}: the algorithm lives in the pure
 * class, this only supplies the data, so which reps are due is unit-testable without a database
 * and swapping the algorithm never touches this wiring. It answers exactly the question the
 * warm-up selector needs - which reps are due today - so "a day's warm-up is drawn from what is
 * actually due" (issue #20's acceptance criterion) is enforced here rather than left to chance.
 *
 * <p>A rep never reviewed is due immediately (cold start, {@link
 * com.sweprep.backend.scheduler.RepSchedule#isDueOn}). A rep reviewed more than once on the same
 * calendar day counts once, using its <em>last</em> outcome that day - the same distinct
 * -calendar-day discipline {@link com.sweprep.backend.learned.LearnedCriterion} applies to
 * "learned" (issue #38), so a rep re-queued and answered wrong then right in one warm-up sitting
 * cannot grind extra spaced credit out of a single session. The two criteria stay deliberately
 * independent derivations over the same submission history - one answers "is this due today",
 * the other "has this been retrieved to criterion" - so this ticket's due-date queue never reads
 * or overrides the learned criterion, and vice versa (the reconciliation issue #20 asked for
 * with issue #56).
 */
@Service
public class RepDueService {

    private final AttemptRepository attempts;
    private final RepScheduler scheduler;
    private final Clock clock;

    public RepDueService(AttemptRepository attempts, RepScheduler scheduler, Clock clock) {
        this.attempts = attempts;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    /**
     * The ids of every {@link Form#REP} exercise in {@code catalog} that is due today for
     * {@code userId}: never reviewed, or its computed due date has arrived.
     */
    public Set<String> dueToday(UUID userId, List<Exercise> catalog) {
        ZoneId zone = clock.getZone();
        LocalDate today = LocalDate.now(clock);
        Map<String, List<Review>> reviewsByExercise = reviewsByExercise(attempts.repReviews(userId), zone);

        Set<String> due = new HashSet<>();
        for (Exercise exercise : catalog) {
            if (exercise.form() != Form.REP) {
                continue;
            }
            List<Review> reviews = reviewsByExercise.getOrDefault(exercise.id(), List.of());
            if (scheduler.schedule(reviews).isDueOn(today)) {
                due.add(exercise.id());
            }
        }
        return due;
    }

    /**
     * Reduces raw attempt rows to one {@link Review} per exercise per calendar day - the last
     * terminal outcome that day, chronologically - then orders each exercise's reviews oldest
     * first, the order {@link RepScheduler#schedule} expects.
     */
    private static Map<String, List<Review>> reviewsByExercise(List<RepReview> rows, ZoneId zone) {
        Map<String, Map<LocalDate, RepReview>> lastPerDay = new HashMap<>();
        for (RepReview row : rows) {
            LocalDate day = LocalDate.ofInstant(row.endedAt(), zone);
            lastPerDay
                    .computeIfAbsent(row.exerciseId(), key -> new HashMap<>())
                    .merge(day, row, (existing, incoming) ->
                            incoming.endedAt().isAfter(existing.endedAt()) ? incoming : existing);
        }
        Map<String, List<Review>> result = new HashMap<>();
        lastPerDay.forEach((exerciseId, byDay) -> result.put(
                exerciseId,
                byDay.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .map(entry -> new Review(
                                entry.getKey(),
                                ReviewQuality.derive(
                                        entry.getValue().solved(),
                                        entry.getValue().explanationRequested(),
                                        entry.getValue().solutionSeen())))
                        .toList()));
        return result;
    }
}
