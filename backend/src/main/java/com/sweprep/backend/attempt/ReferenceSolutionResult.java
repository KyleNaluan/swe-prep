package com.sweprep.backend.attempt;

/**
 * The outcome of an explicit reference-solution reveal (issue #82): the attempt (with
 * {@link Attempt#solutionSeen()} recorded when this was a pre-pass reveal), the
 * disclosed solution text, and whether this reveal happened before the attempt ever
 * passed.
 *
 * <p>{@link #solution} is {@code null} when the exercise carries none to reveal - not a
 * {@code Response.Code} exercise, or content has none authored for it yet.
 *
 * @param attempt the attempt, with {@code solutionSeen} recorded when {@link #prePass}
 * @param solution the disclosed reference solution source, or {@code null} if none
 * @param prePass  whether this reveal happened before the attempt had ever passed - the
 *                 policy boundary that marks solution-seen and affects the spacing
 *                 scheduler and "solved cold"; {@code false} once the attempt is already
 *                 {@code SOLVED}, where the reveal is unrestricted and carries no
 *                 honesty cost
 */
public record ReferenceSolutionResult(AttemptWithCount attempt, String solution, boolean prePass) {}
