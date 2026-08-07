package com.sweprep.backend.attempt;

import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.ExerciseCatalog;
import com.sweprep.backend.exercise.Hint;
import com.sweprep.backend.grader.FailingCase;
import com.sweprep.backend.grader.GraderRegistry;
import com.sweprep.backend.grader.Verdict;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns practice into the durable record the schedulers read (issues #15, #8, #5).
 *
 * <p>The lifecycle is explicit so that abandonment is a recorded outcome rather than
 * an absence: {@link #start} opens an {@code IN_PROGRESS} attempt, {@link #submit}
 * grades and stores each press of Run (marking the attempt {@code SOLVED} the moment
 * one passes), {@link #abandon} records giving up, {@link #takeHint} climbs the hint
 * ladder, and {@link #revealFailingCase} discloses the failing case. Grading itself is
 * delegated to the {@link GraderRegistry}; this service only records what happened.
 *
 * <p>Judging withholds by default (issues #16/#5): a normal verdict tells the solver
 * only how many cases failed, never a value. Hints and the failing-case reveal are the
 * always-available, always-chosen, always-recorded help - and nothing here reduces a
 * score, blocks completion, or ends a sitting. Taking a hint records the rung reached;
 * revealing records the reveal and the one-line hypothesis typed first. Neither is
 * penalised.
 */
@Service
public class AttemptService {

    private final ExerciseCatalog catalog;
    private final GraderRegistry graders;
    private final AttemptRepository attempts;
    private final SubmissionRepository submissions;
    private final CurrentUser currentUser;

    public AttemptService(
            ExerciseCatalog catalog,
            GraderRegistry graders,
            AttemptRepository attempts,
            SubmissionRepository submissions,
            CurrentUser currentUser) {
        this.catalog = catalog;
        this.graders = graders;
        this.attempts = attempts;
        this.submissions = submissions;
        this.currentUser = currentUser;
    }

    /** Opens a new sitting with an exercise, snapshotting its title, domain and form. */
    @Transactional
    public Attempt start(String exerciseId) {
        Exercise exercise = catalog
                .byId(exerciseId)
                .orElseThrow(() -> new AttemptNotFoundException(
                        "No exercise with id '" + exerciseId + "'"));
        Attempt attempt = new Attempt(
                UUID.randomUUID(),
                currentUser.id(),
                exercise.id(),
                exercise.title(),
                exercise.domain(),
                exercise.form().name(),
                AttemptOutcome.IN_PROGRESS,
                Instant.now(),
                null,
                0,
                false,
                null,
                null,
                null,
                null);
        attempts.insert(attempt);
        return attempt;
    }

    /**
     * Grades one press of Run within an attempt and stores it as a submission. If the
     * verdict passes, the attempt is marked solved. Returns the stored submission,
     * which carries the verdict.
     */
    @Transactional
    public Submission submit(UUID attemptId, String response) {
        Attempt attempt = requireOwned(attemptId);
        if (attempt.outcome() != AttemptOutcome.IN_PROGRESS) {
            throw new IllegalAttemptStateException(
                    "Attempt " + attemptId + " has already ended (" + attempt.outcome() + ")");
        }
        Exercise exercise = catalog
                .byId(attempt.exerciseId())
                .orElseThrow(() -> new AttemptNotFoundException(
                        "Exercise '" + attempt.exerciseId() + "' is no longer available"));

        Verdict verdict = graders.grade(exercise, response);
        Submission submission = new Submission(
                UUID.randomUUID(),
                attempt.id(),
                Instant.now(),
                response,
                verdict.outcome(),
                verdict.passed(),
                verdict.total(),
                verdict.detail(),
                verdict.runtimeMillis());
        submissions.insert(submission);

        if (verdict.outcome() == Verdict.Outcome.PASSED) {
            attempts.update(attempt.withOutcome(AttemptOutcome.SOLVED, Instant.now()));
        }
        return submission;
    }

    /**
     * Records that the solver gave up on an open attempt, returning it with its live
     * submission count. Only an {@code IN_PROGRESS} attempt is transitioned; the locking
     * read in {@link #requireOwned} means a racing solve wins, so an abandon can never
     * clobber a sitting that has already been marked solved.
     */
    @Transactional
    public AttemptWithCount abandon(UUID attemptId) {
        Attempt attempt = requireOwned(attemptId);
        if (attempt.outcome() != AttemptOutcome.IN_PROGRESS) {
            throw new IllegalAttemptStateException(
                    "Attempt " + attemptId + " has already ended (" + attempt.outcome() + ")");
        }
        Attempt abandoned = attempt.withOutcome(AttemptOutcome.ABANDONED, Instant.now());
        attempts.update(abandoned);
        return withCount(abandoned);
    }

    /**
     * Discloses the failing case on an open attempt when the solver explicitly asks
     * (issues #16/#5), recording the reveal and the one-line hypothesis they typed
     * first. Both are recorded, never penalised, and the reveal does not end the
     * sitting. The disclosed case is graded from the {@code submission} the solver has
     * in the editor now; it may be {@code null} when there is nothing to show (the
     * submission passed, did not compile, timed out, or the exercise is not judged by
     * test cases). The hypothesis is ungraded and may be blank - skipping it is allowed.
     */
    @Transactional
    public RevealResult revealFailingCase(UUID attemptId, String submission, String hypothesis) {
        Attempt attempt = requireOwned(attemptId);
        if (attempt.outcome() != AttemptOutcome.IN_PROGRESS) {
            throw new IllegalAttemptStateException(
                    "Attempt " + attemptId + " has already ended (" + attempt.outcome() + ")");
        }
        Exercise exercise = catalog
                .byId(attempt.exerciseId())
                .orElseThrow(() -> new AttemptNotFoundException(
                        "Exercise '" + attempt.exerciseId() + "' is no longer available"));

        FailingCase failingCase = graders.firstFailingCase(exercise, submission).orElse(null);
        Attempt revealed = attempt.withFailingCaseRevealed(blankToNull(hypothesis));
        attempts.update(revealed);
        return new RevealResult(withCount(revealed), failingCase);
    }

    /**
     * Climbs one rung of the exercise's hint ladder on an open attempt (issue #16),
     * recording the number of rungs reached. Help is always available and never
     * penalised: taking a hint changes no score and does not end the sitting. Returns
     * the rung just disclosed, or a result with no rung when the ladder is exhausted or
     * the exercise offers no hints.
     */
    @Transactional
    public HintResult takeHint(UUID attemptId) {
        Attempt attempt = requireOwned(attemptId);
        if (attempt.outcome() != AttemptOutcome.IN_PROGRESS) {
            throw new IllegalAttemptStateException(
                    "Attempt " + attemptId + " has already ended (" + attempt.outcome() + ")");
        }
        Exercise exercise = catalog
                .byId(attempt.exerciseId())
                .orElseThrow(() -> new AttemptNotFoundException(
                        "Exercise '" + attempt.exerciseId() + "' is no longer available"));

        List<Hint> ladder = exercise.hints();
        int total = ladder.size();
        int taken = attempt.hintsTaken();
        if (taken >= total) {
            // Ladder already exhausted (or empty): nothing further to reveal, and the
            // count is not advanced past the rungs that exist.
            return new HintResult(withCount(attempt), taken, total, null);
        }
        Hint rung = ladder.get(taken);
        Attempt advanced = attempt.withHintsTaken(taken + 1);
        attempts.update(advanced);
        return new HintResult(withCount(advanced), taken + 1, total, rung);
    }

    /** The current user's practice history, newest first, each with its submission count. */
    public List<AttemptWithCount> history() {
        List<Attempt> found = attempts.findByUser(currentUser.id());
        Map<UUID, Integer> counts =
                submissions.countsForAttempts(found.stream().map(Attempt::id).toList());
        return found.stream()
                .map(a -> new AttemptWithCount(a, counts.getOrDefault(a.id(), 0)))
                .toList();
    }

    private AttemptWithCount withCount(Attempt attempt) {
        return new AttemptWithCount(attempt, submissions.countByAttempt(attempt.id()));
    }

    // Reads an attempt under a row lock (all callers are state-changing and
    // transactional), so a concurrent submit and abandon on the same sitting serialise
    // rather than each committing over the other's outcome.
    private Attempt requireOwned(UUID attemptId) {
        Attempt attempt = attempts
                .findByIdForUpdate(attemptId)
                .orElseThrow(() -> new AttemptNotFoundException("No attempt with id " + attemptId));
        // Single-user today, but ownership is still checked so history can never cross
        // users if a real account mechanism ever lands (issue #14's discipline).
        if (!attempt.userId().equals(currentUser.id())) {
            throw new AttemptNotFoundException("No attempt with id " + attemptId);
        }
        return attempt;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
