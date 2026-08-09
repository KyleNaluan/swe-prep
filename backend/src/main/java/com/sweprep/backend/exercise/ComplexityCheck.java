package com.sweprep.backend.exercise;

import java.util.Objects;

/**
 * Optional content metadata (issue #17) for the complexity self-report flow: the
 * authored target complexity to reveal only after the solver states a claim, and,
 * optionally, how to synthesize growing-size inputs to check that claim empirically.
 *
 * <p>{@code generator} is nullable by design, not a missing piece: not every
 * exercise's problem shape lends itself to a simple synthetic input generator, and
 * an exercise without one still asks for and reveals a target complexity - it just
 * skips the empirical measurement (the acceptance criterion "an exercise with no
 * input generator skips the check entirely without error"). An exercise carrying no
 * {@code ComplexityCheck} at all skips the whole flow: {@link Exercise#complexityCheck()}
 * is itself nullable, so there is nothing to prompt for or reveal.
 *
 * <p>Only {@link #targetTime} is ever checked against measurement: scaling measures
 * wall-clock time, so there is nothing empirical to verify a space claim against.
 * {@link #targetSpace} is still collected and revealed - articulating it is itself
 * the interview skill this ticket trains - but {@code complexityClaimCorrect} on the
 * attempt describes the time claim only.
 *
 * @param targetTime  the authored time complexity, revealed only after the claim
 * @param targetSpace the authored space complexity, revealed only after the claim,
 *                    never empirically checked
 * @param generator   how to synthesize growing-size inputs for measurement, or
 *                    {@code null} when this exercise has none
 */
public record ComplexityCheck(Complexity targetTime, Complexity targetSpace, InputGenerator generator) {

    public ComplexityCheck {
        Objects.requireNonNull(targetTime, "targetTime");
        Objects.requireNonNull(targetSpace, "targetSpace");
    }
}
