package com.sweprep.backend.challenge;

import com.sweprep.backend.attempt.AttemptOutcome;
import com.sweprep.backend.attempt.AttemptRepository;
import com.sweprep.backend.attempt.AttemptRepository.ChallengeAttemptRow;
import com.sweprep.backend.attempt.CurrentUser;
import com.sweprep.backend.attempt.SubmissionRepository;
import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.ExerciseCatalog;
import com.sweprep.backend.exercise.Form;
import com.sweprep.backend.scheduler.ChallengeCandidate;
import com.sweprep.backend.scheduler.ChallengePriority;
import com.sweprep.backend.scheduler.ChallengeQuality;
import com.sweprep.backend.scheduler.ChallengeSchedulerProperties;
import com.sweprep.backend.scheduler.Review;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Wires attempt history and the catalog into the pure {@link ChallengePriority} (issue
 * #21): today's single main-exercise slot is not owed by a due date, it is the
 * highest-scoring {@code CHALLENGE}. Like {@link com.sweprep.backend.learned.LearnedService}
 * and {@link com.sweprep.backend.reps.RepDueService}, this is only the wiring - every
 * scoring rule lives in the pure {@code scheduler} classes so it is unit-testable without
 * a database.
 *
 * <p>Three things get built here that the pure scorer cannot derive for itself:
 *
 * <ul>
 *   <li>Each challenge's {@link Review} history, collapsed from {@link
 *       AttemptRepository#challengeReviews} and {@link SubmissionRepository#countsForAttempts}
 *       via {@link #qualityOf} - the attempt-record-to-quality-score reduction happens here
 *       and nowhere downstream ever sees the raw fields again. A self-check challenge
 *       (ends {@code EXPLAINED}, not {@code SOLVED}/{@code ABANDONED}) gets a fixed
 *       scheduling-only score rather than {@link ChallengeQuality#derive} - see {@link
 *       #qualityOf} for why.
 *   <li>Each challenge's topic coverage, from {@link AttemptRepository#solvedColdExerciseIds}
 *       (the same "solved without help" signal the readiness picture, issue #45, already
 *       reads as the honest competence bar) reduced to a per-topic fraction across the
 *       whole {@code CHALLENGE} catalog.
 *   <li>How many challenges have already been introduced this calendar week, from {@link
 *       AttemptRepository#firstChallengeAttemptDates} - the weekly cap's raw count.
 * </ul>
 */
@Service
public class ChallengeService {

    private final ExerciseCatalog catalog;
    private final AttemptRepository attempts;
    private final SubmissionRepository submissions;
    private final CurrentUser currentUser;
    private final ChallengeSchedulerProperties properties;
    private final Clock clock;
    private final ZoneId zone;

    public ChallengeService(
            ExerciseCatalog catalog,
            AttemptRepository attempts,
            SubmissionRepository submissions,
            CurrentUser currentUser,
            ChallengeSchedulerProperties properties,
            Clock clock) {
        this.catalog = catalog;
        this.attempts = attempts;
        this.submissions = submissions;
        this.currentUser = currentUser;
        this.properties = properties;
        this.clock = clock;
        this.zone = clock.getZone();
    }

    /** Today's single best use of a challenge slot for the current user, if any exists. */
    public Optional<Exercise> selectMain() {
        return selectMain(currentUser.id());
    }

    /**
     * Today's single best use of a challenge slot for a given user - empty only when the
     * catalog holds no {@code CHALLENGE}, or every one is gated out (within its minimum
     * interval, or new and past the weekly cap).
     */
    public Optional<Exercise> selectMain(UUID userId) {
        List<Exercise> challenges =
                catalog.all().stream().filter(exercise -> exercise.form() == Form.CHALLENGE).toList();
        if (challenges.isEmpty()) {
            return Optional.empty();
        }

        LocalDate today = LocalDate.now(clock);
        Map<String, List<Review>> reviewsByExercise = reviewsByExercise(userId);
        Map<String, Double> topicCoverage = topicCoverageByExercise(challenges, userId);
        int newIntroductionsThisWeek = newIntroductionsThisWeek(userId, today);

        List<ChallengeCandidate> candidates = challenges.stream()
                .map(exercise -> new ChallengeCandidate(
                        exercise.id(),
                        reviewsByExercise.getOrDefault(exercise.id(), List.of()),
                        topicCoverage.getOrDefault(exercise.id(), 1.0)))
                .toList();

        return ChallengePriority.select(candidates, today, properties, newIntroductionsThisWeek)
                .flatMap(catalog::byId);
    }

    // Every CHALLENGE exercise's terminal history, reduced to Reviews (oldest first) and
    // grouped by exercise id. The 0-5 collapse happens here, once, via ChallengeQuality.
    private Map<String, List<Review>> reviewsByExercise(UUID userId) {
        List<ChallengeAttemptRow> rows = attempts.challengeReviews(userId);
        Map<UUID, Integer> submissionCounts = submissions.countsForAttempts(
                rows.stream().map(ChallengeAttemptRow::attemptId).toList());

        Map<String, List<Review>> byExercise = new HashMap<>();
        for (ChallengeAttemptRow row : rows) {
            int quality = qualityOf(row, submissionCounts.getOrDefault(row.attemptId(), 0));
            LocalDate day = LocalDate.ofInstant(row.endedAt(), zone);
            byExercise.computeIfAbsent(row.exerciseId(), id -> new ArrayList<>()).add(new Review(day, quality));
        }
        byExercise.replaceAll(
                (id, reviews) -> reviews.stream().sorted(Comparator.comparing(Review::reviewedOn)).toList());
        return byExercise;
    }

    /**
     * The 0-5 quality score for one terminal {@code CHALLENGE} attempt row. A self-check
     * item (issue #41) ends {@link AttemptOutcome#EXPLAINED} rather than {@code
     * SOLVED}/{@code ABANDONED}, and carries no machine correctness verdict to grade -
     * there is no "solved" or "failed" to feed {@link ChallengeQuality#derive}. It is given
     * a fixed {@link ChallengeQuality#WEAK_PASS} here <strong>purely as a scheduling-timing
     * signal</strong>: just enough for the priority scorer to see the attempt happened and
     * apply the minimum-interval floor and overdue tracking to it (issue #21 acceptance
     * criterion 2 - without this, a self-check challenge's {@link Review} history would
     * stay permanently empty, and it could be re-selected as the very next day's main).
     * This value must never be read as - or allowed to feed - an objective machine-verdict
     * competence score: {@link com.sweprep.backend.learned.LearnedCriterion} and the
     * readiness "solved cold" axis are already structurally blind to a self-rated attempt
     * (they read only {@code outcome = 'PASSED'} / {@code 'SOLVED'} rows respectively), and
     * that boundary is not to be crossed by adding a new path into either from here.
     */
    private static int qualityOf(ChallengeAttemptRow row, int submissionCount) {
        if (row.outcome() == AttemptOutcome.EXPLAINED) {
            return ChallengeQuality.WEAK_PASS;
        }
        return ChallengeQuality.derive(
                row.outcome() == AttemptOutcome.SOLVED,
                submissionCount,
                row.hintsTaken(),
                row.failingCaseRevealed(),
                row.complexityClaimCorrect());
    }

    // Per-exercise topic coverage: the average, across an exercise's own topics, of how
    // much of the CHALLENGE catalog tagged with that topic has already been solved cold
    // (issue #45's honest "solved without help" signal - help taken is already tracked,
    // so a coverage bar this generous would be dishonest about what is actually covered).
    private Map<String, Double> topicCoverageByExercise(List<Exercise> challenges, UUID userId) {
        Set<String> solvedCold = attempts.solvedColdExerciseIds(userId);

        Map<String, int[]> perTopic = new HashMap<>(); // topic -> [covered, total]
        for (Exercise exercise : challenges) {
            boolean covered = solvedCold.contains(exercise.id());
            for (String topic : exercise.topics()) {
                int[] counts = perTopic.computeIfAbsent(topic, ignored -> new int[2]);
                counts[1]++;
                if (covered) {
                    counts[0]++;
                }
            }
        }
        Map<String, Double> coverageByTopic = new HashMap<>();
        perTopic.forEach((topic, counts) ->
                coverageByTopic.put(topic, counts[1] == 0 ? 1.0 : (double) counts[0] / counts[1]));

        Map<String, Double> coverageByExercise = new HashMap<>();
        for (Exercise exercise : challenges) {
            if (exercise.topics().isEmpty()) {
                // Nothing to be under-covered on; neutral, not a bonus.
                coverageByExercise.put(exercise.id(), 1.0);
                continue;
            }
            double average = exercise.topics().stream()
                    .mapToDouble(topic -> coverageByTopic.getOrDefault(topic, 1.0))
                    .average()
                    .orElse(1.0);
            coverageByExercise.put(exercise.id(), average);
        }
        return coverageByExercise;
    }

    // How many CHALLENGE exercises had their first-ever sitting fall within the calendar
    // week containing `today` (Monday start) - the weekly new-introduction cap's count.
    private int newIntroductionsThisWeek(UUID userId, LocalDate today) {
        LocalDate weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        Map<String, Instant> firstAttempts = attempts.firstChallengeAttemptDates(userId);
        return (int) firstAttempts.values().stream()
                .map(instant -> LocalDate.ofInstant(instant, zone))
                .filter(date -> !date.isBefore(weekStart))
                .count();
    }
}
