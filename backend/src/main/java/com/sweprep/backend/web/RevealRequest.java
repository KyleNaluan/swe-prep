package com.sweprep.backend.web;

/**
 * An explicit request to reveal the failing case (issues #16/#5). It carries the
 * answer the solver has in the editor now, graded to find the case to disclose, and
 * the one-line hypothesis they typed first about what is wrong.
 *
 * <p>The hypothesis is ungraded and never penalised, and skipping it is allowed: it
 * may be {@code null} or blank, and the reveal still proceeds (issue #16, pedagogy
 * audit). Requiring it to be non-empty is the front end's gentle nudge, not a wire
 * contract.
 *
 * @param submission the answer to grade for the failing case
 * @param hypothesis the solver's one-line guess at the bug, or {@code null}/blank
 */
public record RevealRequest(String submission, String hypothesis) {}
