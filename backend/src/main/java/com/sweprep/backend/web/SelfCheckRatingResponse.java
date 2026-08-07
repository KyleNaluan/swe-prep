package com.sweprep.backend.web;

import com.sweprep.backend.attempt.SelfCheckRating;

/**
 * The editor's answer to a self-check self-rating (issue #41): the attempt now terminal at
 * {@code EXPLAINED}, and the rating recorded. The rating is a generation signal only - it
 * never reduces a score, blocks completion, or feeds the objective competence number.
 *
 * @param attempt the attempt with the self-check marked {@code EXPLAINED}
 * @param rating  the recorded self-rating name
 */
public record SelfCheckRatingResponse(AttemptView attempt, String rating) {

    static SelfCheckRatingResponse of(SelfCheckRating result) {
        return new SelfCheckRatingResponse(
                AttemptView.of(result.attempt()), result.rating().name());
    }
}
