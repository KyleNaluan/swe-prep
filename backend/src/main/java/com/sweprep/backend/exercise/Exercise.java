package com.sweprep.backend.exercise;

import java.util.List;

/**
 * An exercise, independent of any language.
 *
 * <p>This is the domain model the abstraction decision (issue #6) makes real: an
 * exercise carries a prompt, the {@link Difficulty} and {@link #topics()} the
 * scheduler will weigh, its {@link Form} (a rep or a challenge, an attribute here
 * rather than a subtype), a {@link Response} spec saying how it is answered, and a
 * {@link Grading} spec saying how the answer is judged. It says nothing about how
 * it is executed or graded: the response drives a language adapter, and the
 * grading selects a grader (which may need no runner at all). Keeping all of this
 * out of the exercise is what lets a concept question and a coding problem share
 * one model, one loader and one seam.
 *
 * @param id         stable identifier, unique across the content set
 * @param title      short title shown above the prompt
 * @param statement  the prompt, as Markdown-friendly plain text
 * @param domain     the domain it belongs to, e.g. {@code "algorithms"} or
 *                   {@code "fundamentals"}; the session is domain-agnostic (issue #3)
 * @param topics     topic tags used later by the scheduler; never assumed non-empty
 * @param difficulty how hard it is
 * @param form       whether it is a {@link Form#REP} or a {@link Form#CHALLENGE}
 * @param response   how the solver answers it
 * @param grading    how the answer is judged
 */
public record Exercise(
        String id,
        String title,
        String statement,
        String domain,
        List<String> topics,
        Difficulty difficulty,
        Form form,
        Response response,
        Grading grading) {

    public Exercise {
        topics = List.copyOf(topics);
    }
}
