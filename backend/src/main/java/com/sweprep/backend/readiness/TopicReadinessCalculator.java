package com.sweprep.backend.readiness;

import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.Form;
import com.sweprep.backend.learned.LearnedState;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pure derivation of the two topic-level axes issue #22 adds to the readiness picture -
 * {@code shakyTopics} and {@code staleTopics} - from data {@link ReadinessService}
 * already reads. Kept separate from that service and unit-tested directly, the same
 * "pure rules, thin wiring" split as {@link com.sweprep.backend.learned.LearnedCriterion}
 * and {@link com.sweprep.backend.reps.ConfusionPairs}.
 *
 * <p>Both axes share one boundary: a topic with no attempted exercise is neither shaky
 * nor stale, it is simply <em>not covered</em> - a topic the learner has never engaged
 * with cannot be "struggling" or "going cold" on it. That distinction is what keeps
 * these two lists from just restating {@link ReadinessSummary#checksToCriterion}'s
 * coverage gap under a different name.
 */
public final class TopicReadinessCalculator {

    private TopicReadinessCalculator() {}

    /**
     * Topics tagged on at least one attempted {@link Form#REP} exercise whose learned
     * ratio across every {@code REP} tagged with that topic (attempted or not - an
     * unattempted sibling pattern still drags the ratio down, since it is not yet
     * reliable either) falls below {@link ReadinessProperties#shakyThreshold()}. Scoped
     * to {@code REP}-form exercises, the same scope {@code checksToCriterion} uses, so
     * "shaky" is read against the same "pattern" set as "covered".
     */
    public static List<String> shakyTopics(
            List<Exercise> exercises,
            Map<String, LearnedState> learnedStates,
            Set<String> attemptedExerciseIds,
            ReadinessProperties properties) {
        List<Exercise> reps =
                exercises.stream().filter(e -> e.form() == Form.REP).toList();

        Set<String> topics = new TreeSet<>();
        reps.forEach(e -> topics.addAll(e.topics()));

        List<String> shaky = new ArrayList<>();
        for (String topic : topics) {
            List<Exercise> tagged =
                    reps.stream().filter(e -> e.topics().contains(topic)).toList();
            boolean anyAttempted = tagged.stream().anyMatch(e -> attemptedExerciseIds.contains(e.id()));
            if (!anyAttempted) {
                continue; // not covered, not shaky - a different axis entirely
            }
            long learned = tagged.stream()
                    .filter(e -> {
                        LearnedState state = learnedStates.get(e.id());
                        return state != null && state.isLearned();
                    })
                    .count();
            double ratio = (double) learned / tagged.size();
            if (ratio < properties.shakyThreshold()) {
                shaky.add(topic);
            }
        }
        return shaky;
    }

    /**
     * Topics whose most recently attempted exercise - across every {@link Exercise},
     * {@code REP} or {@code CHALLENGE} - was opened at least {@link
     * ReadinessProperties#staleAfterDays()} days before {@code today}. Sorted most stale
     * first, so the most urgent gap leads.
     *
     * @param lastTouchedDates each attempted exercise's most recent sitting, already
     *                         reduced from an {@link java.time.Instant} to a calendar
     *                         day in the app's clock zone (see {@link ReadinessService})
     */
    public static List<StaleTopic> staleTopics(
            List<Exercise> exercises,
            Set<String> attemptedExerciseIds,
            Map<String, LocalDate> lastTouchedDates,
            LocalDate today,
            ReadinessProperties properties) {
        Set<String> topics = new TreeSet<>();
        exercises.forEach(e -> topics.addAll(e.topics()));

        List<StaleTopic> stale = new ArrayList<>();
        for (String topic : topics) {
            LocalDate lastTouched = exercises.stream()
                    .filter(e -> e.topics().contains(topic))
                    .filter(e -> attemptedExerciseIds.contains(e.id()))
                    .map(e -> lastTouchedDates.get(e.id()))
                    .filter(java.util.Objects::nonNull)
                    .max(Comparator.naturalOrder())
                    .orElse(null);
            if (lastTouched == null) {
                continue; // never touched - not covered, not stale
            }
            long daysSince = ChronoUnit.DAYS.between(lastTouched, today);
            if (daysSince >= properties.staleAfterDays()) {
                stale.add(new StaleTopic(topic, daysSince));
            }
        }
        stale.sort(Comparator.comparingLong(StaleTopic::daysSinceTouched).reversed()
                .thenComparing(StaleTopic::topic));
        return stale;
    }
}
