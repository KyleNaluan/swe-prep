package com.sweprep.backend.attempt;

import com.sweprep.backend.exercise.Hint;

/**
 * The outcome of taking a hint (issue #16): the attempt with its climbed-rung count
 * recorded, and the rung that was just revealed.
 *
 * <p>{@link #revealed} is {@code null} when there was no further rung to give - the
 * ladder is already exhausted, or the exercise offers no hints at all. Taking a hint
 * is recorded visibly ({@link #rungsTaken}) but never penalised.
 *
 * @param attempt    the attempt with {@code hints_taken} updated
 * @param rungsTaken how many rungs have now been climbed (the number reached)
 * @param totalRungs how many rungs the ladder holds in all
 * @param revealed   the rung just disclosed, or {@code null} if there was none left
 */
public record HintResult(AttemptWithCount attempt, int rungsTaken, int totalRungs, Hint revealed) {}
