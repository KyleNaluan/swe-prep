package com.sweprep.backend.advisor;

import com.sweprep.backend.complexity.ComplexityBucket;
import com.sweprep.backend.exercise.Complexity;

/**
 * The three-way comparison at the center of the LLM complexity second opinion
 * (issue #83): the solver's claim, the model's independent reading, and - when
 * empirical scaling measurement (issue #17) reached one - the measured bucket. Pure
 * and stateless, like {@link com.sweprep.backend.complexity.ComplexityClassifier}, so
 * it is fully unit-testable with no network call.
 *
 * <p>Design decisions made at the curriculum level (issue #83), implemented here
 * rather than re-litigated: <strong>disagreement is the product</strong> - when every
 * voice actually present agrees, there is nothing to show beyond quiet confirmation
 * ({@link #agreement()} {@code true}, {@link #prompt()} {@code null}); the moment any
 * two disagree, the whole comparison is rendered as a neutral question for the
 * learner to resolve in their own words, never as a verdict naming which voice is
 * right - the model's reading is advisory only, and a confidently wrong verdict in a
 * study tool is worse than no verdict. Measurement is compared at {@link
 * ComplexityBucket} granularity - the coarsest of the three, and the same
 * granularity {@link com.sweprep.backend.attempt.AttemptService#claimComplexity}
 * already judges a claim against - so a claim and a model reading that differ only
 * within one bucket (e.g. {@code LINEAR} vs {@code LINEARITHMIC}) are not treated as
 * a disagreement measurement could never have surfaced either.
 *
 * @param agreement true when every voice actually present shares one bucket
 * @param prompt    the neutral, resolve-it-yourself question naming every reading,
 *                  or {@code null} when {@link #agreement()} is {@code true}
 */
public record ComplexityDisagreement(boolean agreement, String prompt) {

    /**
     * @param claimedTime   the solver's self-reported time complexity
     * @param measuredBucket what empirical measurement concluded, or {@code null} when
     *                      it was skipped or inconclusive - an absent voice, not a
     *                      dissenting one
     * @param modelTime     the model's own reading
     */
    public static ComplexityDisagreement evaluate(
            Complexity claimedTime, ComplexityBucket measuredBucket, Complexity modelTime) {
        ComplexityBucket claimedBucket = ComplexityBucket.of(claimedTime);
        ComplexityBucket modelBucket = ComplexityBucket.of(modelTime);

        boolean allAgree = modelBucket == claimedBucket
                && (measuredBucket == null || measuredBucket == claimedBucket);
        if (allAgree) {
            return new ComplexityDisagreement(true, null);
        }
        return new ComplexityDisagreement(
                false, buildPrompt(claimedTime, measuredBucket, modelTime));
    }

    private static String buildPrompt(Complexity claimedTime, ComplexityBucket measuredBucket, Complexity modelTime) {
        StringBuilder prompt = new StringBuilder("You claimed ")
                .append(bigO(claimedTime))
                .append(", ");
        if (measuredBucket != null) {
            prompt.append("measurement found ").append(bigO(measuredBucket)).append(", ");
        }
        prompt.append("and the model reads this as ")
                .append(bigO(modelTime))
                .append(" - which is right, and why?");
        return prompt.toString();
    }

    private static String bigO(Complexity complexity) {
        return switch (complexity) {
            case CONSTANT -> "O(1)";
            case LOGARITHMIC -> "O(log n)";
            case LINEAR -> "O(n)";
            case LINEARITHMIC -> "O(n log n)";
            case QUADRATIC -> "O(n²)";
            case CUBIC -> "O(n³)";
            case EXPONENTIAL -> "O(2ⁿ)";
        };
    }

    private static String bigO(ComplexityBucket bucket) {
        return switch (bucket) {
            case SUBLINEAR -> "O(1) or O(log n)";
            case LINEAR -> "O(n) or O(n log n)";
            case QUADRATIC -> "O(n²)";
            case CUBIC -> "O(n³)";
            case EXPONENTIAL -> "O(2ⁿ)";
        };
    }
}
