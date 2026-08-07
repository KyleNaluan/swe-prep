package com.sweprep.backend.grader;

import com.sweprep.backend.exercise.Exercise;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Routes each exercise to the one {@link Grader} that can judge its grading spec.
 * This is the seam that makes the grader polymorphic: the web layer asks the
 * registry to grade, and never has to know whether that runs code (test cases) or
 * not (a fixed answer key). Adding a new grading kind is a new {@code Grader} bean
 * plus its {@code supports} check, with nothing here to change.
 */
@Component
public class GraderRegistry {

    private final List<Grader> graders;

    public GraderRegistry(List<Grader> graders) {
        this.graders = List.copyOf(graders);
    }

    public Verdict grade(Exercise exercise, String submission) {
        return graderFor(exercise).grade(exercise, submission);
    }

    /**
     * The first case this submission fails, when the solver explicitly asks to see it
     * (issues #16/#5). Routes to the same grader that judges the exercise; empty when
     * that grader has no failing case to disclose (a fixed-answer exercise, or a run
     * that produced no per-case result).
     */
    public Optional<FailingCase> firstFailingCase(Exercise exercise, String submission) {
        return graderFor(exercise).firstFailingCase(exercise, submission);
    }

    private Grader graderFor(Exercise exercise) {
        return graders.stream()
                .filter(grader -> grader.supports(exercise))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No grader supports exercise '" + exercise.id() + "'"));
    }
}
