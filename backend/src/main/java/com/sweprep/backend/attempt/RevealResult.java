package com.sweprep.backend.attempt;

import com.sweprep.backend.grader.FailingCase;

/**
 * The outcome of an explicit failing-case reveal (issues #16/#5): the attempt with
 * the reveal now recorded on it, and the failing case that was disclosed.
 *
 * <p>{@link #failingCase} is {@code null} when there was none to show - the current
 * submission passed, did not compile, timed out, or the exercise is not judged by
 * test cases at all. The reveal is still recorded on the attempt in that case, since
 * the solver did ask; it never reduces a score or ends the sitting.
 *
 * @param attempt     the attempt with the reveal (and any hypothesis) recorded
 * @param failingCase the disclosed case, or {@code null} if there was none
 */
public record RevealResult(AttemptWithCount attempt, FailingCase failingCase) {}
