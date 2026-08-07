package com.sweprep.backend.attempt;

import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.ExerciseCatalog;
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
 * one passes), {@link #abandon} records giving up, and {@link #recordFailingCaseReveal}
 * marks the reveal (issue #5, never penalised). Grading itself is delegated to the
 * {@link GraderRegistry}; this service only records what happened.
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
                verdict.detail());
        submissions.insert(submission);

        if (verdict.outcome() == Verdict.Outcome.PASSED) {
            attempts.update(withOutcome(attempt, AttemptOutcome.SOLVED));
        }
        return submission;
    }

    /** Records that the solver gave up on an open attempt. */
    @Transactional
    public Attempt abandon(UUID attemptId) {
        Attempt attempt = requireOwned(attemptId);
        if (attempt.outcome() != AttemptOutcome.IN_PROGRESS) {
            throw new IllegalAttemptStateException(
                    "Attempt " + attemptId + " has already ended (" + attempt.outcome() + ")");
        }
        Attempt abandoned = withOutcome(attempt, AttemptOutcome.ABANDONED);
        attempts.update(abandoned);
        return abandoned;
    }

    /**
     * Records that the failing case was revealed during an open attempt (issue #5).
     * The reveal is recorded, never penalised; the actual failing-case content is the
     * judging ticket's concern, not this one's.
     */
    @Transactional
    public Attempt recordFailingCaseReveal(UUID attemptId) {
        Attempt attempt = requireOwned(attemptId);
        if (attempt.outcome() != AttemptOutcome.IN_PROGRESS) {
            throw new IllegalAttemptStateException(
                    "Attempt " + attemptId + " has already ended (" + attempt.outcome() + ")");
        }
        Attempt revealed = new Attempt(
                attempt.id(),
                attempt.userId(),
                attempt.exerciseId(),
                attempt.exerciseTitle(),
                attempt.domain(),
                attempt.form(),
                attempt.outcome(),
                attempt.startedAt(),
                attempt.endedAt(),
                attempt.hintsTaken(),
                true,
                attempt.complexityClaim(),
                attempt.measuredComplexity(),
                attempt.complexityClaimCorrect());
        attempts.update(revealed);
        return revealed;
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

    private Attempt requireOwned(UUID attemptId) {
        Attempt attempt = attempts
                .findById(attemptId)
                .orElseThrow(() -> new AttemptNotFoundException("No attempt with id " + attemptId));
        // Single-user today, but ownership is still checked so history can never cross
        // users if a real account mechanism ever lands (issue #14's discipline).
        if (!attempt.userId().equals(currentUser.id())) {
            throw new AttemptNotFoundException("No attempt with id " + attemptId);
        }
        return attempt;
    }

    private static Attempt withOutcome(Attempt attempt, AttemptOutcome outcome) {
        return new Attempt(
                attempt.id(),
                attempt.userId(),
                attempt.exerciseId(),
                attempt.exerciseTitle(),
                attempt.domain(),
                attempt.form(),
                outcome,
                attempt.startedAt(),
                Instant.now(),
                attempt.hintsTaken(),
                attempt.failingCaseRevealed(),
                attempt.complexityClaim(),
                attempt.measuredComplexity(),
                attempt.complexityClaimCorrect());
    }
}
