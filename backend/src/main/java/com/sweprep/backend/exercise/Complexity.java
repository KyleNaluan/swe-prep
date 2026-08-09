package com.sweprep.backend.exercise;

/**
 * The coarse complexity-class vocabulary the self-report and target-reveal flow
 * (issue #17) is written in - a closed set the solver picks from, not a free-form
 * "O(...)" string. This is deliberate, not a UI shortcut: the empirical check that
 * verifies a claim (see {@link ComplexityCheck}) can only ever discriminate
 * polynomial degree, never a constant factor, so the claim vocabulary itself is
 * bounded to what that check can actually speak to.
 */
public enum Complexity {
    CONSTANT,
    LOGARITHMIC,
    LINEAR,
    LINEARITHMIC,
    QUADRATIC,
    CUBIC,
    EXPONENTIAL
}
