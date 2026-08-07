package com.sweprep.backend.grader;

import com.sweprep.backend.exercise.Exercise;

/**
 * Decides whether a submission passes. A grader owns the pass/fail decision; it
 * delegates any execution to a runner rather than executing itself. Keeping the
 * decision separate from execution is what lets a concept exercise be graded with
 * no runner at all (see the exercise abstraction, issue #6).
 *
 * <p>Graders are polymorphic over an exercise's {@code Grading} spec: each
 * declares, via {@link #supports}, which exercises it can judge, and a dispatcher
 * routes each exercise to the one grader that handles it. That is what makes the
 * "grader with no runner" real - {@code AnswerKeyGrader} judges a fixed-answer
 * exercise and never touches a runner, while {@code TestCaseGrader} does.
 */
public interface Grader {

    /** Whether this grader can judge the given exercise's grading spec. */
    boolean supports(Exercise exercise);

    /** Judge {@code submission} against {@code exercise}. Only call when {@link #supports} is true. */
    Verdict grade(Exercise exercise, String submission);
}
