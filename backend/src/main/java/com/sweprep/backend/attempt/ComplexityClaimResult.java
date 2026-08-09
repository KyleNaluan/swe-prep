package com.sweprep.backend.attempt;

import com.sweprep.backend.complexity.MeasurementOutcome;
import com.sweprep.backend.exercise.Complexity;

/**
 * The outcome of recording a complexity claim (issue #17): the attempt with the claim,
 * measured complexity and match flag now recorded, the authored target - revealed here
 * for the first time, never before this call - and what empirical measurement found.
 *
 * @param attempt     the attempt with the claim, measured complexity and match flag
 * @param targetTime  the authored time complexity
 * @param targetSpace the authored space complexity (self-reported only - never
 *                    empirically checked, see {@link
 *                    com.sweprep.backend.exercise.ComplexityCheck})
 * @param measurement what empirical scaling measurement concluded
 */
public record ComplexityClaimResult(
        AttemptWithCount attempt, Complexity targetTime, Complexity targetSpace, MeasurementOutcome measurement) {}
