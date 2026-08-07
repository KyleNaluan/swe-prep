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
public sealed interface Response permits Response.Code, Response.Choice, Response.FreeText {

    /**
     * The solver writes code implementing a method. The editor is seeded with a
     * generated stub for this {@link Signature}, and the same signature drives the
     * generated harness that calls the submission.
     */
    record Code(Signature signature) implements Response {}

    /**
     * The solver picks one of a fixed list of {@link Option}s. Nothing is compiled or
     * run, so an exercise with this response and an {@link Grading.AnswerKey} needs no
     * runner at all.
     *
     * <p>Each option carries its visible text and, for a distractor, the misconception
     * it targets (issue #42) - so a choice's wrong options are annotated at the point
     * they are declared. The editor is served only the option {@code text}
     * (see {@link #optionTexts()}); the misconceptions are authoring metadata the loader
     * validates but never ships.
     */
    record Choice(List<Option> options) implements Response {

        public Choice {
            options = List.copyOf(options);
        }

        /** The visible option texts, in order - what the editor renders and the learner picks. */
        public List<String> optionTexts() {
            return options.stream().map(Option::text).toList();
        }
    }

    /**
     * The solver types free-form text into a box. Nothing is compiled or run, so an
     * exercise with this response needs no runner. It is the response for two very
     * different items, distinguished by their {@link Grading}: paired with a
     * {@link Grading.SelfCheck} it is a self-graded "explain in your own words" item
     * the machine never judges (design revision t3, section 1.1); paired with a
     * {@link Grading.AnswerKey} it is a machine-graded short answer (a
     * "predict the output" rep). The response itself carries nothing beyond being a
     * text box - what is expected of the text lives in the grading spec.
     */
    record FreeText() implements Response {}
}
