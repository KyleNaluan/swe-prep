package com.sweprep.backend.attempt;

/**
 * An {@link Attempt} paired with how many submissions it holds - the shape history
 * is read in, so a caller need not re-count. Submission count is one of the signals
 * the scheduler (issue #8) collapses into its quality score.
 */
public record AttemptWithCount(Attempt attempt, int submissionCount) {}
