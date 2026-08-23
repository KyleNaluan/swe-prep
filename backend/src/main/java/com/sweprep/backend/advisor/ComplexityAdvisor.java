package com.sweprep.backend.advisor;

import com.sweprep.backend.exercise.Exercise;

/**
 * The seam the LLM complexity second opinion (issue #83) is built behind: nothing
 * outside this package may know which model, or which HTTP client, answers a
 * request - the shape {@link com.sweprep.backend.scheduler.RepScheduler} already
 * establishes for a swappable algorithm. {@link AnthropicComplexityAdvisor} is the
 * one real implementation; tests exercise everything else against a small fake, per
 * the ticket's explicit "put the model call behind a seam" instruction.
 *
 * <p>{@link #available} is the mechanism behind "missing API key means the feature is
 * simply absent, not broken": a caller must check it before ever calling {@link
 * #read}, and it is answerable with no network call, so the button that triggers this
 * feature can be hidden with zero latency and zero risk of a broken action reaching
 * the server.
 */
public interface ComplexityAdvisor {

    /** Whether this advisor is configured to call the model at all. No network call. */
    boolean available();

    /**
     * Asks the model to read {@code submissionSource}'s time complexity, one short
     * completion. Never called when {@link #available()} is {@code false}.
     *
     * @throws ComplexityAdvisorException if the call fails or the model's answer
     *                                    cannot be read as a {@link ModelComplexityReading}
     */
    ModelComplexityReading read(Exercise exercise, String submissionSource, String language);
}
