package com.sweprep.backend.web;

import com.sweprep.backend.attempt.SelfRating;
import java.util.UUID;

/**
 * The body of a self-check self-rating (issue #41): which committed submission is being
 * rated, and how the learner judged their explanation against the revealed model answer.
 *
 * @param submission the id of the revealed self-check submission to rate
 * @param rating     the learner's self-rating
 */
public record SelfCheckRatingRequest(UUID submission, SelfRating rating) {}
