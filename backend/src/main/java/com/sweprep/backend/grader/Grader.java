package com.sweprep.backend.grader;

import com.sweprep.backend.exercise.Exercise;
import java.util.Optional;

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

    /**
     * Judge {@code submission}, written in {@code language} (a {@link
     * com.sweprep.backend.language.LanguageAdapter#languageId()}), against {@code
     * exercise} (issue #26: the user can choose which language to solve in). Only a
     * code submission judged by running it has any use for this - {@link
     * com.sweprep.backend.grader.TestCaseGrader} overrides it to pick the matching
     * adapter and runner; every grader with no runner at all (a fixed answer key, a
     * SQL query, a self-check) has no notion of "language" and simply ignores it via
     * this default, which delegates to the plain {@link #grade(Exercise, String)}.
     */
    default Verdict grade(Exercise exercise, String submission, String language) {
        return grade(exercise, submission);
    }

    /**
     * Disclose the first case this submission fails, when the solver explicitly asks
     * for it (issues #16/#5). This is deliberately separate from {@link #grade}: a
     * normal verdict withholds every case value and reveals only the failing count,
     * and only an explicit reveal request calls this to give one case up.
     *
     * <p>A grader that judges against a fixed answer (no cases) has nothing to reveal,
     * so the default returns empty; the test-case grader overrides it. An empty result
     * also means there was no isolable failing case - the submission passed, or it did
     * not compile, timed out, or otherwise produced no per-case result to compare.
     */
    default Optional<FailingCase> firstFailingCase(Exercise exercise, String submission) {
        return Optional.empty();
    }

    /** {@link #firstFailingCase(Exercise, String)}, for a submission written in {@code language}. */
    default Optional<FailingCase> firstFailingCase(Exercise exercise, String submission, String language) {
        return firstFailingCase(exercise, submission);
    }
}
