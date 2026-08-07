package com.sweprep.backend.grader;

import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.Grading;
import org.springframework.stereotype.Component;

/**
 * Handles an exercise whose grading spec is a {@link Grading.SelfCheck}: a
 * produce-then-reveal item the machine never judges (design revision t3, section
 * 1.1). Like {@link AnswerKeyGrader} it has <em>no runner</em> - it compiles and
 * runs nothing - but it goes one step further and issues <em>no verdict</em>
 * either. Its whole job is to {@link #reveal} the model answer once the solver has
 * committed their own text, so they can grade themselves.
 *
 * <p>It is still a {@link Grader} bean so that {@link GraderRegistry} routes to it
 * by {@link #supports} like every other grading kind. But the boundary the
 * revision is emphatic about - a self-check must never emit a machine verdict and
 * must never corrupt the scheduler - is kept in the type itself, not left to
 * callers: {@link #grade} throws rather than returning a fabricated pass/fail. A
 * self-rating is not a trustworthy 0-5, so there is deliberately no code path that
 * turns a self-check into a {@link Verdict}. The objective learning signal stays
 * with the machine-graded Checks.
 */
@Component
public class SelfCheckGrader implements Grader {

    @Override
    public boolean supports(Exercise exercise) {
        return exercise.grading() instanceof Grading.SelfCheck;
    }

    /**
     * Never valid for a self-check: there is no machine verdict to produce.
     * Returning a pass/fail here would smuggle an untrustworthy self-rating into the
     * objective learning signal, which is exactly what the revision forbids. Callers
     * reveal the model answer with {@link #reveal} and record the solver's own
     * self-rating; the machine judges nothing.
     *
     * @throws UnsupportedOperationException always
     */
    @Override
    public Verdict grade(Exercise exercise, String submission) {
        throw new UnsupportedOperationException(
                "A self-check exercise ('" + exercise.id() + "') is never machine-graded: it"
                        + " reveals a model answer for self-assessment and emits no verdict"
                        + " (design revision t3, section 1.1). Call reveal(...) instead.");
    }

    /**
     * The model answer to show the solver after they commit their produced text, so
     * they can grade themselves against it. No runner, no comparison, no verdict.
     *
     * @throws IllegalArgumentException if the exercise is not self-check graded
     */
    public String reveal(Exercise exercise) {
        if (!(exercise.grading() instanceof Grading.SelfCheck selfCheck)) {
            throw new IllegalArgumentException(
                    "Exercise '" + exercise.id() + "' is not self-check graded");
        }
        return selfCheck.modelAnswer();
    }
}
