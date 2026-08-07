package com.sweprep.backend.attempt;

/**
 * The outcome of a learner self-rating their revealed self-check explanation (issue #41):
 * the attempt now terminal at {@link AttemptOutcome#EXPLAINED}, and the {@link SelfRating}
 * recorded against its submission.
 *
 * <p>The rating never reduces a score, blocks completion, or feeds the objective competence
 * signal - it is the separate generation signal the design revision names (section 4.1).
 *
 * @param attempt the attempt with the self-check marked {@code EXPLAINED}
 * @param rating  the self-rating recorded
 */
public record SelfCheckRating(AttemptWithCount attempt, SelfRating rating) {}
