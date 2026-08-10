package com.sweprep.backend.readiness;

/**
 * An honest "X of Y" count - the shared shape every axis of the readiness picture is
 * expressed in (issue #45): a concepts-covered count, an objective competence axis, and
 * each per-family breakdown line are all just this. Deliberately not a percentage or a
 * score: the map's "no invented currency" ruling (issue #7) rules out compressing a real
 * count into a single opaque number, so the raw numerator and denominator travel to the
 * surface together and the UI decides how to show them.
 *
 * @param achieved how many are done (learned, solved cold, read) - never negative, never
 *                 more than {@link #total}
 * @param total    how many exist in the relevant scope (the whole catalog, or one family)
 */
public record Progress(int achieved, int total) {

    public Progress {
        if (total < 0 || achieved < 0 || achieved > total) {
            throw new IllegalArgumentException(
                    "Progress requires 0 <= achieved <= total, got achieved=" + achieved
                            + " total=" + total);
        }
    }
}
