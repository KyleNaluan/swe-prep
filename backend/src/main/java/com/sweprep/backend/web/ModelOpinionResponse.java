package com.sweprep.backend.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sweprep.backend.attempt.ModelOpinionResult;

/**
 * The editor's answer to a model complexity second opinion request (issue #83): the
 * model's own reading and reasoning, plus the three-way comparison against the
 * solver's claim and the empirical measurement. {@code agreement} true means every
 * voice actually present agreed - render quiet confirmation, nothing more.
 * {@code agreement} false means {@code disagreementPrompt} carries the neutral,
 * resolve-it-yourself question naming every reading; it is never worded as the model
 * being right or wrong, matching the advisory-only design (issue #83).
 *
 * @param modelTime          the model's own reading of the time complexity
 * @param modelReasoning     the model's reasoning, shown alongside the reading -
 *                           never the bucket alone
 * @param agreement          whether every voice actually present agreed
 * @param disagreementPrompt the drill prompt to show, or omitted when {@code agreement}
 *                           is true
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModelOpinionResponse(
        String modelTime, String modelReasoning, boolean agreement, String disagreementPrompt) {

    static ModelOpinionResponse of(ModelOpinionResult result) {
        return new ModelOpinionResponse(
                result.modelTime().name(),
                result.modelReasoning(),
                result.disagreement().agreement(),
                result.disagreement().prompt());
    }
}
