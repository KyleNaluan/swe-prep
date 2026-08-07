package com.sweprep.backend.attempt;

/**
 * The outcome of one press of Run (issue #15): the stored {@link Submission} carrying
 * the verdict, plus the check's {@code explanation} when a wrong answer earns its
 * automatic disclosure (issue #51).
 *
 * <p>{@link #explanation} is non-null only when it should be shown now - the verdict is
 * a genuine wrong answer ({@code FAILED}) and the check carries an explanation. It is
 * {@code null} for a passing answer (where the explanation is instead one keystroke
 * away on request), for an execution problem that is not a wrong answer (a compile
 * error or timeout), and for a check that carries no explanation. Showing it here is
 * automatic, not a request, so nothing is recorded on the attempt and nothing is
 * penalised.
 *
 * @param submission  the persisted submission and its verdict
 * @param explanation the explanation to show automatically, or {@code null}
 */
public record SubmitResult(Submission submission, String explanation) {}
