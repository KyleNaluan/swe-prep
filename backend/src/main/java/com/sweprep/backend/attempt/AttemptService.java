package com.sweprep.backend.attempt;

import com.sweprep.backend.commit.SolutionCommitResult;
import com.sweprep.backend.commit.SolutionCommitService;
import com.sweprep.backend.complexity.ComplexityBucket;
import com.sweprep.backend.complexity.MeasurementOutcome;
import com.sweprep.backend.complexity.ScalingMeasurer;
import com.sweprep.backend.exercise.ComplexityCheck;
import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.ExerciseCatalog;
import com.sweprep.backend.exercise.Grading;
import com.sweprep.backend.exercise.Hint;
import com.sweprep.backend.grader.FailingCase;
import com.sweprep.backend.grader.GraderRegistry;
import com.sweprep.backend.grader.SelfCheckGrader;
import com.sweprep.backend.grader.Verdict;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
 *
 * <p>The check's explanation (issue #51) is separate from that help. It is shown
 * automatically on a wrong answer by {@link #submit} (a disclosure, not a request, so
 * nothing is recorded), and disclosed on request by {@link #requestExplanation} when
 * correct - where the request is recorded as its own confidence signal, kept distinct
 * from the hint count so "took a hint" never blurs into "read why I was wrong".
 */
@Service
public class AttemptService {

    private final ExerciseCatalog catalog;
    private final GraderRegistry graders;
    private final SelfCheckGrader selfCheckGrader;
    private final ScalingMeasurer measurer;
    private final AttemptRepository attempts;
    private final SubmissionRepository submissions;
    private final CurrentUser currentUser;
    private final TransactionTemplate transactionTemplate;
    private final SolutionCommitService solutionCommitService;

    public AttemptService(
            ExerciseCatalog catalog,
            GraderRegistry graders,
            SelfCheckGrader selfCheckGrader,
            ScalingMeasurer measurer,
            AttemptRepository attempts,
            SubmissionRepository submissions,
            CurrentUser currentUser,
            PlatformTransactionManager transactionManager,
            SolutionCommitService solutionCommitService) {
        this.catalog = catalog;
        this.graders = graders;
        this.selfCheckGrader = selfCheckGrader;
        this.measurer = measurer;
        this.attempts = attempts;
        this.submissions = submissions;
        this.currentUser = currentUser;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.solutionCommitService = solutionCommitService;
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
                false,
                null,
                null,
                null);
        attempts.insert(attempt);
        return attempt;
    }

    /**
     * Grades one press of Run within an attempt and stores it as a submission. If the
     * verdict passes, the attempt is marked solved. Returns the stored submission and,
     * when a wrong answer earns it, the check's explanation to show automatically
     * (issue #51): the explanation is disclosed on a {@code FAILED} verdict when the
     * check carries one, and withheld otherwise (a passing answer offers it on request
     * instead, and an execution problem is not a wrong answer). This automatic
     * disclosure is not a request and records nothing.
     *
     * <p>When this submission freshly solves the attempt, the solution is committed to
     * the private content repo afterward (issue #22) - deliberately <em>outside</em> the
     * database transaction that records the submission, the same discipline {@link
     * #claimComplexity} uses for measurement: git I/O (writing a file, committing,
     * pushing) must never hold the attempt's row lock or its pooled connection. The
     * submission is durable either way; the commit is a best-effort side effect that
     * never affects grading (see {@link com.sweprep.backend.commit.SolutionCommitService}).
     */
    public SubmitResult submit(UUID attemptId, String response) {
        Submitted recorded = recordSubmission(attemptId, response);
        SolutionCommitResult commitResult = recorded.freshSolve()
                ? solutionCommitService.commitSolution(recorded.exercise(), response)
                : null;
        boolean committed = commitResult != null && commitResult.committed();
        return new SubmitResult(recorded.submission(), recorded.explanationOnWrong(), committed);
    }

    @Transactional
    Submitted recordSubmission(UUID attemptId, String response) {
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
                SubmissionOutcome.of(verdict.outcome()),
                verdict.passed(),
                verdict.total(),
                verdict.detail(),
                verdict.runtimeMillis());
        submissions.insert(submission);

        boolean freshSolve = verdict.outcome() == Verdict.Outcome.PASSED;
        if (freshSolve) {
            attempts.update(attempt.withOutcome(AttemptOutcome.SOLVED, Instant.now()));
        }
        String explanationOnWrong =
                verdict.outcome() == Verdict.Outcome.FAILED ? exercise.explanation() : null;
        return new Submitted(submission, explanationOnWrong, freshSolve, exercise);
    }

    // The DB-committed outcome of one submission, plus what submit() needs afterward to
    // decide whether to trigger a solution commit, without re-reading the exercise.
    private record Submitted(Submission submission, String explanationOnWrong, boolean freshSolve, Exercise exercise) {}

    /**
     * Commits a self-check "explain in your own words" answer and reveals the model answer
     * for self-comparison (issue #41, design revision t3 section 1.1). Nothing is
     * machine-graded: the produced text is stored as a {@link SubmissionOutcome#SELF_RATED}
     * submission and the model answer is handed back for the learner to judge themselves.
     *
     * <p>The order is load-bearing and enforced here, not just in the editor. The produced
     * text must be non-blank - you cannot reveal before producing - and the submission is
     * inserted <em>before</em> the model answer is returned, freezing what the learner wrote
     * cold. That is what makes a later self-rating after peeking distinguishable: the record
     * holds the pre-reveal text, not a copy edited once the answer was in view. Each reveal
     * is its own committed submission, so re-typing and revealing again is a fresh honest
     * commit rather than a rewrite of the first.
     *
     * @throws IllegalAttemptStateException    if the attempt has already ended
     * @throws InvalidAttemptRequestException if the exercise is not self-check graded, or the
     *                                         produced text is blank
     */
    @Transactional
    public SelfCheckReveal revealSelfCheck(UUID attemptId, String produced) {
        Attempt attempt = requireInProgress(attemptId);
        Exercise exercise = requireExercise(attempt);
        if (!(exercise.grading() instanceof Grading.SelfCheck)) {
            throw new InvalidAttemptRequestException(
                    "Exercise '" + exercise.id() + "' is not a self-check item; there is no model"
                            + " answer to reveal for self-assessment");
        }
        if (produced == null || produced.isBlank()) {
            throw new InvalidAttemptRequestException(
                    "Produce an explanation before revealing the model answer");
        }
        String modelAnswer = selfCheckGrader.reveal(exercise);
        Submission submission = new Submission(
                UUID.randomUUID(),
                attempt.id(),
                Instant.now(),
                produced.strip(),
                SubmissionOutcome.SELF_RATED,
                0,
                0,
                "",
                0);
        submissions.insert(submission);
        return new SelfCheckReveal(submission, modelAnswer);
    }

    /**
     * Records the learner's self-rating of a revealed self-check answer and ends the sitting
     * as {@link AttemptOutcome#EXPLAINED} (issue #41). The rating is stamped onto the
     * already-committed submission (design revision t3 section 5, in {@code detail} - no
     * migration); it is a separate generation signal, never the objective competence number,
     * which cannot see a {@code SELF_RATED} row.
     *
     * <p>Rating is only possible for a submission that this attempt actually revealed, which
     * enforces reveal-before-rate: the submission exists only because {@link #revealSelfCheck}
     * created it. Rating once ends the sitting, so it cannot be redone - a self-check verdict
     * is committed exactly as irreversibly as a solve.
     *
     * @throws IllegalAttemptStateException if the attempt has already ended
     * @throws AttemptNotFoundException     if the submission is unknown or not a self-check
     *                                      commit of this attempt
     */
    @Transactional
    public SelfCheckRating rateSelfCheck(UUID attemptId, UUID submissionId, SelfRating rating) {
        Attempt attempt = requireInProgress(attemptId);
        Submission submission = submissions
                .findById(submissionId)
                .filter(s -> s.attemptId().equals(attempt.id()))
                .filter(s -> s.outcome() == SubmissionOutcome.SELF_RATED)
                .orElseThrow(() -> new AttemptNotFoundException(
                        "No revealed self-check submission " + submissionId + " on attempt "
                                + attemptId + "; reveal the model answer before rating"));
        submissions.recordSelfRating(submission.id(), rating.name());
        Attempt explained = attempt.withOutcome(AttemptOutcome.EXPLAINED, Instant.now());
        attempts.update(explained);
        return new SelfCheckRating(withCount(explained), rating);
    }

    /**
     * Records the solver's self-reported complexity for a solved attempt and, in the
     * same response, reveals the authored target alongside what empirical scaling
     * measurement found (issue #17). The order is load-bearing and enforced here, not
     * just in the editor: the claim is written to the attempt <em>before</em> the target
     * is read back, and the target is never returned from any earlier call - {@code
     * ExerciseView} ships only whether a target exists, never its value - so there is no
     * way for the client to already be holding it while this prompt renders.
     *
     * <p>Requires a {@code SOLVED} attempt (the acceptance criterion "after tests pass")
     * whose exercise declares a {@link ComplexityCheck}, and only once per attempt: a
     * second call is rejected rather than letting a solver retry claims against the same
     * measurement. The submission measured is the one that solved the attempt - {@link
     * #submit} refuses every further call once an attempt is {@code SOLVED}, so the last
     * submission on a solved attempt is exactly that one.
     *
     * @throws AttemptNotFoundException       if the attempt or its exercise is unknown
     * @throws IllegalAttemptStateException   if the attempt is not yet solved, or has
     *                                        already recorded a complexity claim
     * @throws InvalidAttemptRequestException if the exercise carries no complexity check
     */
    public ComplexityClaimResult claimComplexity(UUID attemptId, ComplexityClaim claim) {
        // Validate and gather the inputs with a plain read - measurement forks a JVM per
        // configured size and can run for tens of seconds, so it must never hold the
        // attempt's row lock (or its pooled connection). The short check-then-update
        // transaction below re-reads the row under a lock and re-checks these guards, so
        // the ordering and one-claim-per-attempt guarantees survive the unlocked read.
        Attempt attempt = requireOwnedUnlocked(attemptId);
        requireClaimable(attempt, attemptId);
        Exercise exercise = requireExercise(attempt);
        ComplexityCheck check = exercise.complexityCheck();
        if (check == null) {
            throw new InvalidAttemptRequestException(
                    "Exercise '" + exercise.id() + "' has no complexity target to claim against");
        }

        List<Submission> submitted = submissions.findByAttempt(attempt.id());
        String passingSubmission =
                submitted.isEmpty() ? null : submitted.get(submitted.size() - 1).response();

        MeasurementOutcome measurement = measurer.measure(exercise, passingSubmission);
        Boolean claimCorrect = switch (measurement) {
            case MeasurementOutcome.Skipped ignored -> null;
            case MeasurementOutcome.Inconclusive ignored -> null;
            case MeasurementOutcome.Conclusive conclusive ->
                    conclusive.bucket() == ComplexityBucket.of(claim.time());
        };
        String measuredText = switch (measurement) {
            case MeasurementOutcome.Skipped ignored -> null;
            case MeasurementOutcome.Inconclusive ignored -> "INCONCLUSIVE";
            case MeasurementOutcome.Conclusive conclusive ->
                    conclusive.bucket().name() + ":" + String.format("%.2f", conclusive.exponent());
        };

        return transactionTemplate.execute(status -> {
            Attempt locked = requireOwned(attemptId);
            requireClaimable(locked, attemptId);
            Attempt recorded = locked.withComplexity(claim.serialize(), measuredText, claimCorrect);
            attempts.update(recorded);
            return new ComplexityClaimResult(
                    withCount(recorded), check.targetTime(), check.targetSpace(), measurement);
        });
    }

    // The preconditions for claiming complexity, checked both before measurement (to fail
    // fast and cheaply) and again under the row lock before the write (to keep the
    // one-claim-per-attempt guarantee across concurrent claims).
    private void requireClaimable(Attempt attempt, UUID attemptId) {
        if (attempt.outcome() != AttemptOutcome.SOLVED) {
            throw new IllegalAttemptStateException(
                    "Attempt " + attemptId + " is not solved yet; complexity is claimed only "
                            + "after a passing submission");
        }
        if (attempt.complexityClaim() != null) {
            throw new IllegalAttemptStateException(
                    "Attempt " + attemptId + " has already recorded a complexity claim");
        }
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

    /**
     * Discloses the check's explanation when the solver explicitly asks (issue #51),
     * recording the request as its own confidence signal. This is the "one keystroke
     * away when correct" path: unlike the automatic disclosure on a wrong answer it is a
     * request, so it is recorded - but deliberately in {@code explanation_requested},
     * not in the hint count, since asking why a correct answer is correct is not asking
     * for help to solve. It is never penalised and never ends the sitting.
     *
     * <p>Withhold-by-default is a training decision the API - not just the editor -
     * enforces, so the request is only honoured from a terminal attempt: {@code SOLVED}
     * (the "when correct" path) or {@code ABANDONED} (giving up then reading why is
     * legitimate, and is already recorded as abandonment, not as confidence). An
     * {@code IN_PROGRESS} attempt is rejected with {@link IllegalAttemptStateException},
     * so a solver can never read the explanation before answering. Returns the
     * explanation, which is {@code null} when the check carries none (the request is
     * still recorded, since the solver did ask).
     */
    @Transactional
    public ExplanationResult requestExplanation(UUID attemptId) {
        Attempt attempt = requireOwned(attemptId);
        if (attempt.outcome() == AttemptOutcome.IN_PROGRESS) {
            throw new IllegalAttemptStateException(
                    "Attempt " + attemptId + " is still in progress; its explanation is withheld until it ends");
        }
        Exercise exercise = catalog
                .byId(attempt.exerciseId())
                .orElseThrow(() -> new AttemptNotFoundException(
                        "Exercise '" + attempt.exerciseId() + "' is no longer available"));

        Attempt recorded = attempt.withExplanationRequested();
        attempts.update(recorded);
        return new ExplanationResult(withCount(recorded), exercise.explanation());
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

    // Reads an owned attempt and requires it to still be open, the precondition every
    // state-changing lifecycle step shares (an already-ended attempt is a 409).
    private Attempt requireInProgress(UUID attemptId) {
        Attempt attempt = requireOwned(attemptId);
        if (attempt.outcome() != AttemptOutcome.IN_PROGRESS) {
            throw new IllegalAttemptStateException(
                    "Attempt " + attemptId + " has already ended (" + attempt.outcome() + ")");
        }
        return attempt;
    }

    private Exercise requireExercise(Attempt attempt) {
        return catalog
                .byId(attempt.exerciseId())
                .orElseThrow(() -> new AttemptNotFoundException(
                        "Exercise '" + attempt.exerciseId() + "' is no longer available"));
    }

    // Reads an attempt under a row lock (all callers are state-changing and
    // transactional), so a concurrent submit and abandon on the same sitting serialise
    // rather than each committing over the other's outcome.
    private Attempt requireOwned(UUID attemptId) {
        return requireOwned(attempts.findByIdForUpdate(attemptId), attemptId);
    }

    // Reads an owned attempt without a row lock, for a caller that will do expensive,
    // non-transactional work before re-reading under a lock to write (see
    // claimComplexity). No caller may write off the attempt this returns.
    private Attempt requireOwnedUnlocked(UUID attemptId) {
        return requireOwned(attempts.findById(attemptId), attemptId);
    }

    private Attempt requireOwned(Optional<Attempt> found, UUID attemptId) {
        Attempt attempt =
                found.orElseThrow(() -> new AttemptNotFoundException("No attempt with id " + attemptId));
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
