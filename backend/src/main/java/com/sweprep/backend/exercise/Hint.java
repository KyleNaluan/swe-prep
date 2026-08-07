package com.sweprep.backend.exercise;

/**
 * One rung of an exercise's hint ladder (issue #16). The ladder is an ordered list
 * of these on the {@link Exercise}, climbed from the least revealing rung to the
 * most: by convention pattern name, then approach, then key insight, then solution.
 *
 * <p>A rung is content, authored per exercise in the private content repo (issue
 * #4/#14) and loaded like the rest of the exercise. The order is the list order; the
 * ladder is not a fixed four rungs in the model, so an exercise may offer fewer or
 * more, and one with no ladder simply carries an empty list. The rungs are never
 * shipped to the editor up front - the editor learns only their {@link #name}s and
 * requests each {@link #body} explicitly, so that taking a hint is always a recorded
 * choice, never something the solver stumbles into (issue #16, issue #5).
 *
 * @param name  a short label for the rung, e.g. {@code "Pattern"} or {@code "Approach"}
 * @param body  the hint text revealed when this rung is taken
 */
public record Hint(String name, String body) {}
