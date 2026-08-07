package com.sweprep.backend.exercise;

import java.util.List;

/**
 * An exercise, independent of any language.
 *
 * <p>An exercise carries a human-readable statement, a language-neutral
 * {@link Signature}, the {@link Comparison} rule that decides when an answer is
 * correct, and a list of language-neutral {@link TestCase}s. It says nothing
 * about how it is executed or graded: that is the job of a language adapter, a
 * runner and a grader, kept deliberately separate (see the exercise abstraction
 * decision on the planning map, issue #6).
 *
 * @param id         stable identifier
 * @param title      short title shown above the editor
 * @param statement  the problem statement, as Markdown-friendly plain text
 * @param signature  the method to implement
 * @param comparison how an answer is compared to the expected value; defaults to
 *                   {@link Comparison#exact()} when not given
 * @param testCases  the cases the submission is graded against
 */
public record Exercise(
        String id,
        String title,
        String statement,
        Signature signature,
        Comparison comparison,
        List<TestCase> testCases) {

    public Exercise {
        comparison = comparison == null ? Comparison.exact() : comparison;
        testCases = List.copyOf(testCases);
    }
}
