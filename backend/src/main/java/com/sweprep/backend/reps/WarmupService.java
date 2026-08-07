package com.sweprep.backend.reps;

import com.sweprep.backend.attempt.AttemptRepository;
import com.sweprep.backend.attempt.CurrentUser;
import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.ExerciseCatalog;
import com.sweprep.backend.exercise.Family;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Assembles the current user's warm-up set (issue #18): it gathers the inputs the pure
 * {@link WarmupSelector} needs - the loaded catalog, the active families, and the
 * problems this user has attempted (which gate derived reps) - and hands back the
 * ordered set. Keeping the selection logic in the selector and only the wiring here is
 * what lets the interleaving, family and gating rules be tested without a database.
 */
@Service
public class WarmupService {

    private final ExerciseCatalog catalog;
    private final AttemptRepository attempts;
    private final ConfusionPairService confusionPairs;
    private final CurrentUser currentUser;
    private final WarmupSelector selector;
    private final Set<Family> activeFamilies;

    public WarmupService(
            ExerciseCatalog catalog,
            AttemptRepository attempts,
            ConfusionPairService confusionPairs,
            CurrentUser currentUser,
            RepProperties properties) {
        this.catalog = catalog;
        this.attempts = attempts;
        this.confusionPairs = confusionPairs;
        this.currentUser = currentUser;
        this.selector =
                new WarmupSelector(properties.warmupSize(), properties.maxConsecutiveSame());
        // An empty configured list means "no family restriction yet" (the setting is
        // #40), so every family is treated as active until then.
        this.activeFamilies = properties.activeFamilies().isEmpty()
                ? EnumSet.allOf(Family.class)
                : EnumSet.copyOf(properties.activeFamilies());
    }

    /** The ordered warm-up set for the current user. */
    public List<Exercise> warmup() {
        java.util.UUID userId = currentUser.id();
        Set<String> attemptedProblems = attempts.attemptedExerciseIds(userId);
        return selector.select(
                catalog.all(), activeFamilies, attemptedProblems, confusionPairs.forUser(userId));
    }
}
