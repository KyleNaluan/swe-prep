package com.sweprep.backend.attempt;

import com.sweprep.backend.advisor.ComplexityDisagreement;
import com.sweprep.backend.exercise.Complexity;

/**
 * The outcome of asking for a model complexity second opinion on a solved attempt
 * (issue #83): the model's own reading and reasoning, plus the three-way comparison
 * against the solver's claim and (when conclusive) the empirical measurement. Never
 * persisted - see {@link AttemptService#secondOpinion} - so there is no column this
 * advisory reading could ever feed into grading, the schedule, or readiness; the
 * hard boundary is structural, not a convention callers must remember.
 *
 * @param modelTime      the model's own reading of the time complexity
 * @param modelReasoning the model's reasoning, shown whenever the reading is shown -
 *                       never a bucket alone, which would read as an unexplained verdict
 * @param disagreement   the three-way comparison; {@link ComplexityDisagreement#agreement()}
 *                       {@code true} renders as quiet confirmation, {@code false} as the
 *                       neutral resolve-it-yourself prompt in {@link ComplexityDisagreement#prompt()}
 */
public record ModelOpinionResult(
        Complexity modelTime, String modelReasoning, ComplexityDisagreement disagreement) {}
