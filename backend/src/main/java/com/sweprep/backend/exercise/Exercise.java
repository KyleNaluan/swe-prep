package com.sweprep.backend.exercise;

import java.util.List;

/**
 * An exercise, independent of any language.
 *
 * <p>An exercise carries a human-readable statement, a language-neutral
 * {@link Signature}, and a list of language-neutral {@link TestCase}s. It says
 * nothing about how it is executed or graded: that is the job of a language
 * adapter, a runner and a grader, kept deliberately separate (see the exercise
 * abstraction decision on the planning map, issue #6).
 *
 * @param id        stable identifier
 * @param title     short title shown above the editor
 * @param statement the problem statement, as Markdown-friendly plain text
 * @param signature the method to implement
 * @param testCases the cases the submission is graded against
 */
public record Exercise(
        String id,
        String title,
        String statement,
        Signature signature,
        List<TestCase> testCases) {

    public Exercise {
        testCases = List.copyOf(testCases);
    }
}
