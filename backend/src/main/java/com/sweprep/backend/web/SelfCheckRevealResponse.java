package com.sweprep.backend.web;

import com.sweprep.backend.attempt.SelfCheckReveal;

/**
 * The editor's answer to a self-check reveal (issue #41): the id of the committed
 * submission the later self-rating targets, and the model answer now disclosed for
 * self-comparison.
 *
 * <p>The model answer travels only here, after the learner has committed their own text -
 * never in the up-front {@link ExerciseView}, which would defeat produce-then-reveal.
 *
 * @param submissionId the committed self-check submission, to be self-rated next
 * @param modelAnswer  the model answer to compare against
 */
public record SelfCheckRevealResponse(String submissionId, String modelAnswer) {

    static SelfCheckRevealResponse of(SelfCheckReveal reveal) {
        return new SelfCheckRevealResponse(
                reveal.submission().id().toString(), reveal.modelAnswer());
    }
}
