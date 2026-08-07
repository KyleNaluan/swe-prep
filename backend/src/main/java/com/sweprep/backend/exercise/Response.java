package com.sweprep.backend.exercise;

import java.util.List;

/**
 * The <em>response spec</em>: how a solver answers an exercise, and therefore what
 * the editor renders. It is kept separate from the {@link Grading} spec (how the
 * answer is judged) on purpose - the two vary independently. A code response is
 * usually judged by test cases, but a "predict the output" rep shows code yet is
 * judged against a fixed answer, so response and grading are not one choice.
 *
 * <p>The set of response kinds is a sealed hierarchy, so a new one (free-text
 * short answer, a SQL query) is added as one more permitted record without
 * touching the exercise model.
 */
public sealed interface Response permits Response.Code, Response.Choice {

    /**
     * The solver writes code implementing a method. The editor is seeded with a
     * generated stub for this {@link Signature}, and the same signature drives the
     * generated harness that calls the submission.
     */
    record Code(Signature signature) implements Response {}

    /**
     * The solver picks one of a fixed list of options. Nothing is compiled or run,
     * so an exercise with this response and an {@link Grading.AnswerKey} needs no
     * runner at all.
     */
    record Choice(List<String> options) implements Response {

        public Choice {
            options = List.copyOf(options);
        }
    }
}
