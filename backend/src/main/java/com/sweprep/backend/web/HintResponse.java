package com.sweprep.backend.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sweprep.backend.attempt.HintResult;
import com.sweprep.backend.exercise.Hint;

/**
 * The editor's answer to taking a hint (issue #16): the attempt with its climbed-rung
 * count recorded, and the rung just disclosed.
 *
 * <p>{@code name}/{@code body} are omitted when there was no further rung to give -
 * the ladder is exhausted or the exercise has none. {@code rungsTaken} still reflects
 * how many rungs have been climbed, and {@code totalRungs} how many exist, so the
 * editor can tell "no more hints" from "hint revealed".
 *
 * @param attempt    the attempt with hints_taken updated
 * @param rungsTaken how many rungs have now been climbed (the number reached)
 * @param totalRungs how many rungs the ladder holds
 * @param name       the disclosed rung's name, or {@code null} if none was left
 * @param body       the disclosed rung's text, or {@code null} if none was left
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HintResponse(
        AttemptView attempt, int rungsTaken, int totalRungs, String name, String body) {

    static HintResponse of(HintResult result) {
        Hint rung = result.revealed();
        return new HintResponse(
                AttemptView.of(result.attempt()),
                result.rungsTaken(),
                result.totalRungs(),
                rung == null ? null : rung.name(),
                rung == null ? null : rung.body());
    }
}
