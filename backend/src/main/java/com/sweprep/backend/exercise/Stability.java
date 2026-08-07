package com.sweprep.backend.exercise;

/**
 * How durable a piece of content's subject matter is, which drives the AI/ML
 * refresh policy (design revision t3, section 3.4).
 *
 * <p>{@link #STABLE} content is decades-durable and authored once, like any Core
 * concept - ideal spaced-repetition material with no refresh burden. {@link
 * #VOLATILE} content rots and is governed by a re-verification cadence; it carries
 * a {@code reviewed} date (see {@link Exercise#reviewed()}) and, by authoring rule,
 * is never judged by a fixed answer key that could rot into a confidently-wrong
 * verdict. Content defaults to {@code STABLE}.
 */
public enum Stability {
    /** Decades-durable subject matter; authored once, no refresh policy. */
    STABLE,
    /** Fast-rotting subject matter; carries a {@code reviewed} date and a refresh cadence. */
    VOLATILE
}
