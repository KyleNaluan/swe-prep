package com.sweprep.backend.scheduler;

import java.util.List;

/**
 * One challenge exercise as {@link ChallengePriority} sees it: nothing but the shared
 * 0-5 {@link Review} history the priority score is built from (never a submission count,
 * a hint count, or any other raw attempt field - those are already collapsed into
 * {@link ChallengeQuality#derive} before a candidate is built) plus how well-covered its
 * topics already are.
 *
 * @param exerciseId    the challenge's id
 * @param reviews       this user's terminal {@code CHALLENGE} history for this exercise,
 *                      oldest first; empty means never attempted
 * @param topicCoverage the average, across this exercise's topics, of the fraction of
 *                      same-topic challenges already solved cleanly - 1.0 when the
 *                      exercise carries no topics (nothing to be under-covered on)
 */
public record ChallengeCandidate(String exerciseId, List<Review> reviews, double topicCoverage) {

    public ChallengeCandidate {
        reviews = List.copyOf(reviews);
    }
}
