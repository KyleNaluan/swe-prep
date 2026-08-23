package com.sweprep.backend.advisor;

import com.sweprep.backend.exercise.Exercise;

/**
 * Builds the single user message sent for a model complexity reading (issue #83) -
 * pure string assembly, no network, so prompt construction is unit-testable without
 * a live model call and a change to it shows up as a plain diff in a test, not a
 * silent behavior change.
 *
 * <p>The prompt asks for the reading <em>and</em> its reasoning in the same request
 * (one short completion, per the ticket), since the reasoning is what the
 * disagreement prompt shows the learner - never the bucket alone. It deliberately
 * says nothing about the solver's own claim or the measured result: the model must
 * form its own independent reading, not rationalize toward a value it was handed -
 * the three-way comparison happens afterward, in {@link ComplexityDisagreement}, not
 * here.
 */
final class ComplexityAdvisorPrompt {

    private ComplexityAdvisorPrompt() {}

    static String build(Exercise exercise, String submissionSource, String language) {
        return """
                Read the time complexity of this %s solution, as a function of its input size.

                Problem: %s

                %s

                Solution:
                ```%s
                %s
                ```

                Give the tightest asymptotic time complexity class this code actually exhibits \
                and the reasoning behind it. Pay close attention to costs that are easy to miss: \
                amortised analysis (e.g. a dynamic array that doubles on resize), whether \
                recursion is memoised, and library calls whose own cost is not O(1) - string \
                concatenation in a loop, List.contains, substring and similar copying \
                operations."""
                .formatted(
                        language,
                        exercise.title(),
                        exercise.statement(),
                        language,
                        submissionSource);
    }
}
