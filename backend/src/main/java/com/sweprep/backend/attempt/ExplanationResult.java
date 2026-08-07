package com.sweprep.backend.attempt;

/**
 * The outcome of an explicit explanation request (issue #51): the attempt with the
 * request now recorded on it, and the check's explanation that was disclosed.
 *
 * <p>Asking to see why the correct answer is correct is recorded as its own confidence
 * signal ({@link Attempt#explanationRequested()}), deliberately distinct from taking a
 * hint - it never reduces a score, blocks completion, or ends the sitting. Unlike the
 * automatic disclosure on a wrong answer, this is a request, so it is recorded.
 *
 * <p>{@link #explanation} is {@code null} only when the check carries none; the request
 * is still recorded in that case, since the solver did ask.
 *
 * @param attempt     the attempt with the request recorded
 * @param explanation the disclosed explanation, or {@code null} if the check has none
 */
public record ExplanationResult(AttemptWithCount attempt, String explanation) {}
