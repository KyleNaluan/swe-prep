package com.sweprep.backend.readiness;

import com.sweprep.backend.attempt.AttemptRepository;
import com.sweprep.backend.attempt.CurrentUser;
import com.sweprep.backend.exercise.Content;
import com.sweprep.backend.exercise.ContentCatalog;
import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.Family;
import com.sweprep.backend.exercise.Form;
import com.sweprep.backend.exercise.Lesson;
import com.sweprep.backend.learned.LearnedService;
import com.sweprep.backend.learned.LearnedState;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Builds the honest readiness picture (issue #45, design revision t3 section 4.4) by
 * reading the whole content catalog against the objective signals that already exist:
 * {@link LearnedService}'s successive-relearning state (issue #38) and the machine-verdict
 * facts {@link AttemptRepository} tracks. This is a pure read-side derivation over data the
 * merged schema already holds - like {@code LearnedService} and {@code RepDueService}, it
 * computes on read rather than maintaining a mutable summary row.
 *
 * <p>The three-way separation the ticket requires is structural here, not a naming
 * convention: {@link #checksToCriterion} and {@link #solvedCold} are built only from {@link
 * LearnedService} (which itself reads only {@code outcome = 'PASSED'} rows) and {@link
 * AttemptRepository#solvedColdExerciseIds}, both of which a self-check can never satisfy
 * (design revision t3 section 1.1); {@link #conceptsCovered} counts Lesson reads, a
 * different content kind entirely; and the self-check count is read from a third, disjoint
 * query ({@link AttemptRepository#explainedExerciseIds}) and travels as a bare count,
 * never blended into either {@link Progress}.
 *
 * <p>Per-family scoping (design revision t3 section 5) computes {@link Family} from the
 * live {@link ContentCatalog} rather than a snapshotted column - the optional {@code
 * attempt.family} migration the revision costed out was not taken, so a family whose
 * content is not in the local clone simply is not counted, which is the accepted
 * trade-off for needing no migration.
 *
 * <p>Issue #22 adds two topic-level axes on top of #45's picture, computed by the pure
 * {@link TopicReadinessCalculator} from data already read here: {@code shakyTopics}
 * (attempted patterns not yet reliable) and {@code staleTopics} (patterns not touched
 * in a while), both scoped to only ever flag a topic that has actually been attempted -
 * an untouched topic is "not covered" ({@link #checksToCriterion}), a different axis.
 */
@Service
public class ReadinessService {

    private final ContentCatalog catalog;
    private final LearnedService learned;
    private final AttemptRepository attempts;
    private final CurrentUser currentUser;
    private final ReadinessProperties properties;
    private final Clock clock;

    public ReadinessService(
            ContentCatalog catalog,
            LearnedService learned,
            AttemptRepository attempts,
            CurrentUser currentUser,
            ReadinessProperties properties,
            Clock clock) {
        this.catalog = catalog;
        this.learned = learned;
        this.attempts = attempts;
        this.currentUser = currentUser;
        this.properties = properties;
        this.clock = clock;
    }

    /** The current user's readiness picture. */
    public ReadinessSummary summary() {
        return summary(currentUser.id());
    }

    /** A given user's readiness picture. */
    public ReadinessSummary summary(UUID userId) {
        List<Content> content = catalog.allContent();
        List<Exercise> exercises = content.stream()
                .filter(Exercise.class::isInstance)
                .map(Exercise.class::cast)
                .toList();
        List<Lesson> lessons = content.stream()
                .filter(Lesson.class::isInstance)
                .map(Lesson.class::cast)
                .toList();

        Map<String, LearnedState> learnedStates = learned.statesForAll(userId);
        Set<String> solvedColdIds = attempts.solvedColdExerciseIds(userId);
        Set<String> readLessonIds = attempts.readLessonIds(userId);
        Set<String> explainedIds = attempts.explainedExerciseIds(userId);
        Set<String> attemptedIds = attempts.attemptedExerciseIds(userId);

        Progress checksToCriterion = checksToCriterion(exercises, learnedStates, e -> true);
        Progress solvedCold = solvedCold(exercises, solvedColdIds, e -> true);
        Progress conceptsCovered = new Progress(
                (int) lessons.stream().filter(l -> readLessonIds.contains(l.id())).count(),
                lessons.size());

        List<FamilyReadiness> families = Arrays.stream(Family.values())
                .map(family -> new FamilyReadiness(
                        family,
                        checksToCriterion(exercises, learnedStates, e -> e.family().contains(family)),
                        solvedCold(exercises, solvedColdIds, e -> e.family().contains(family))))
                .toList();

        Map<String, LocalDate> lastTouchedDates = attempts.lastAttemptDates(userId).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> LocalDate.ofInstant(e.getValue(), clock.getZone())));
        LocalDate today = LocalDate.now(clock);
        List<String> shakyTopics =
                TopicReadinessCalculator.shakyTopics(exercises, learnedStates, attemptedIds, properties);
        List<StaleTopic> staleTopics = TopicReadinessCalculator.staleTopics(
                exercises, attemptedIds, lastTouchedDates, today, properties);

        return new ReadinessSummary(
                checksToCriterion,
                solvedCold,
                conceptsCovered,
                explainedIds.size(),
                families,
                shakyTopics,
                staleTopics);
    }

    private static Progress checksToCriterion(
            List<Exercise> exercises, Map<String, LearnedState> states, Predicate<Exercise> scope) {
        List<Exercise> reps = exercises.stream()
                .filter(e -> e.form() == Form.REP)
                .filter(scope)
                .toList();
        int learnedCount = (int) reps.stream()
                .filter(e -> {
                    LearnedState state = states.get(e.id());
                    return state != null && state.isLearned();
                })
                .count();
        return new Progress(learnedCount, reps.size());
    }

    private static Progress solvedCold(
            List<Exercise> exercises, Set<String> solvedColdIds, Predicate<Exercise> scope) {
        List<Exercise> challenges = exercises.stream()
                .filter(e -> e.form() == Form.CHALLENGE)
                .filter(scope)
                .toList();
        int solvedCount = (int) challenges.stream().filter(e -> solvedColdIds.contains(e.id())).count();
        return new Progress(solvedCount, challenges.size());
    }
}
