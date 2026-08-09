package com.sweprep.backend.reps;

import com.sweprep.backend.attempt.AttemptRepository;
import com.sweprep.backend.attempt.CurrentUser;
import com.sweprep.backend.exercise.ContentCatalog;
import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.ExerciseCatalog;
import com.sweprep.backend.exercise.Family;
import com.sweprep.backend.exercise.Lesson;
import com.sweprep.backend.role.RoleService;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Assembles the current user's warm-up set (issues #18, #40): it gathers the inputs the pure
 * {@link WarmupSelector} needs - the loaded catalog, the active families, the opted-in reps, and
 * the problems this user has attempted (which gate derived reps) - and hands back the ordered set.
 * Keeping the selection logic in the selector and only the wiring here is what lets the
 * interleaving, family and gating rules be tested without a database.
 *
 * <p>The active families are the user's own durable choice (issue #40), read from {@link
 * RoleService} rather than from config, so there is one family filter sourced from the role the
 * user picked - not a second parallel one. Two things widen it beyond the strict active set, both
 * from design revision t3 section 2.2:
 *
 * <ul>
 *   <li>a rep the user has <b>attempted</b> stays eligible even if its family later goes inactive,
 *       so deactivating a family never rips an already-due review out of the queue; and
 *   <li>the <b>Checks of a Lesson the user has read</b> are opted in individually, so reading an
 *       inactive-family Lesson pulls that one concept's Checks into the warm-up without turning the
 *       whole family on - the reachability hinge of the filter.
 * </ul>
 *
 * <p>The due-date SM-2 queue (issue #20) is wired in the same way: {@link RepDueService}
 * derives which reps are due today from attempt history, exactly as {@link
 * ConfusionPairService} derives the confusion relation, and the result is handed to the
 * selector as one more filter alongside family and gating.
 */
@Service
public class WarmupService {

    private final ExerciseCatalog catalog;
    private final ContentCatalog contentCatalog;
    private final AttemptRepository attempts;
    private final ConfusionPairService confusionPairs;
    private final RepDueService repDue;
    private final CurrentUser currentUser;
    private final RoleService roles;
    private final WarmupSelector selector;

    public WarmupService(
            ExerciseCatalog catalog,
            ContentCatalog contentCatalog,
            AttemptRepository attempts,
            ConfusionPairService confusionPairs,
            RepDueService repDue,
            CurrentUser currentUser,
            RoleService roles,
            RepProperties properties) {
        this.catalog = catalog;
        this.contentCatalog = contentCatalog;
        this.attempts = attempts;
        this.confusionPairs = confusionPairs;
        this.repDue = repDue;
        this.currentUser = currentUser;
        this.roles = roles;
        this.selector =
                new WarmupSelector(properties.warmupSize(), properties.maxConsecutiveSame());
    }

    /** The ordered warm-up set for the current user, drawn only from what is due today. */
    public List<Exercise> warmup() {
        UUID userId = currentUser.id();
        List<Exercise> allContent = catalog.all();
        Set<Family> activeFamilies = roles.activeFamilies(userId);
        Set<String> attemptedProblems = attempts.attemptedExerciseIds(userId);
        Set<String> optedIn = optedInReps(userId, attemptedProblems);
        Set<String> dueToday = repDue.dueToday(userId, allContent);
        return selector.select(
                allContent,
                activeFamilies,
                optedIn,
                attemptedProblems,
                confusionPairs.forUser(userId),
                dueToday);
    }

    /**
     * The reps that stay warm-up-eligible regardless of their family: every rep the user has
     * attempted (in-flight reviews survive a family deactivation) plus the Checks of every Lesson
     * the user has read (reading opts a concept's Checks in individually). Both are the design's
     * "already pulled in" set (section 2.2).
     */
    private Set<String> optedInReps(UUID userId, Set<String> attemptedProblems) {
        Set<String> optedIn = new HashSet<>(attemptedProblems);
        for (String lessonId : attempts.readLessonIds(userId)) {
            contentCatalog
                    .contentById(lessonId)
                    .filter(Lesson.class::isInstance)
                    .map(Lesson.class::cast)
                    .ifPresent(lesson -> optedIn.addAll(lesson.checks()));
        }
        return optedIn;
    }
}
