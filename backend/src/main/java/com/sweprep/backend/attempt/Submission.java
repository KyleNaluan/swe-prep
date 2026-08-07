package com.sweprep.backend.attempt;

import java.time.Instant;
import java.util.UUID;

/**
 * One commit within an {@link Attempt}: the response the solver sent and the
 * {@link SubmissionOutcome} it earned. Usually that is one press of Run and a machine
 * verdict; for a self-check "explain in your own words" item it is one produce-then-reveal
 * commit, whose {@code outcome} is {@link SubmissionOutcome#SELF_RATED} and whose
 * {@code detail} holds the learner's self-rating (design revision t3, section 1.1). Every
 * submission is kept, not only the last, so the scheduler (issue #8) can see how many tries
 * a sitting took - one of the signals that collapses into its quality score.
 *
 * @param id          stable identifier for this submission
 * @param attemptId   the attempt this submission belongs to
 * @param submittedAt when it was committed
 * @param response    the source written, the option picked, or the free text produced
 *                    (the neutral wire field)
 * @param outcome     the recorded outcome ({@link SubmissionOutcome}) - a machine verdict,
 *                    or {@code SELF_RATED} for a self-check commit
 * @param passed        cases that passed
 * @param total         cases in the exercise
 * @param detail        compiler diagnostics or a timeout note; for a self-check, the
 *                      learner's self-rating; otherwise empty
 * @param runtimeMillis how long the run took, kept for interest only - runtime is
 *                      reported, never graded (issue #16/#5); 0 when no code ran
 */
public record Submission(
        UUID id,
        UUID attemptId,
        Instant submittedAt,
        String response,
        SubmissionOutcome outcome,
        int passed,
        int total,
        String detail,
        long runtimeMillis) {}
