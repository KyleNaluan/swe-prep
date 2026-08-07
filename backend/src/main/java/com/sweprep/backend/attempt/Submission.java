package com.sweprep.backend.attempt;

import com.sweprep.backend.grader.Verdict;
import java.time.Instant;
import java.util.UUID;

/**
 * One press of Run within an {@link Attempt}: the response the solver sent and the
 * {@link Verdict} it earned. Every submission is kept, not only the last, so the
 * scheduler (issue #8) can see how many tries a sitting took - one of the signals
 * that collapses into its quality score.
 *
 * @param id          stable identifier for this submission
 * @param attemptId   the attempt this submission belongs to
 * @param submittedAt when Run was pressed
 * @param response    the source written or the option picked (the neutral wire field)
 * @param outcome     the verdict outcome ({@link Verdict.Outcome})
 * @param passed        cases that passed
 * @param total         cases in the exercise
 * @param detail        compiler diagnostics or a timeout note, otherwise empty
 * @param runtimeMillis how long the run took, kept for interest only - runtime is
 *                      reported, never graded (issue #16/#5); 0 when no code ran
 */
public record Submission(
        UUID id,
        UUID attemptId,
        Instant submittedAt,
        String response,
        Verdict.Outcome outcome,
        int passed,
        int total,
        String detail,
        long runtimeMillis) {}
