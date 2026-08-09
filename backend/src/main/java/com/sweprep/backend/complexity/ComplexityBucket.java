package com.sweprep.backend.complexity;

import com.sweprep.backend.exercise.Complexity;

/**
 * The coarse growth-rate buckets empirical scaling can actually discriminate between
 * (issue #17, the honesty constraint): a doubling-size timing curve reliably tells
 * "the polynomial degree changed" apart from "it didn't", and reliably cannot tell
 * {@link Complexity#LINEAR} from {@link Complexity#LINEARITHMIC} apart - constant
 * factors swamp a log at practical sizes - so those two share {@link #LINEAR} here,
 * and likewise {@link Complexity#CONSTANT}/{@link Complexity#LOGARITHMIC} share
 * {@link #SUBLINEAR}. A claim is compared against measurement one bucket at a time,
 * never finer than this.
 */
public enum ComplexityBucket {
    SUBLINEAR,
    LINEAR,
    QUADRATIC,
    CUBIC,
    EXPONENTIAL;

    /** The bucket a self-reported or authored {@link Complexity} claim falls into. */
    public static ComplexityBucket of(Complexity complexity) {
        return switch (complexity) {
            case CONSTANT, LOGARITHMIC -> SUBLINEAR;
            case LINEAR, LINEARITHMIC -> LINEAR;
            case QUADRATIC -> QUADRATIC;
            case CUBIC -> CUBIC;
            case EXPONENTIAL -> EXPONENTIAL;
        };
    }
}
