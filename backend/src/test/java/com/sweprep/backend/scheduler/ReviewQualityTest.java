package com.sweprep.backend.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Proves the quality derivation directly, per the ticket's ask that it be "a small, directly
 * testable unit rather than buried inside the scheduler" (issue #20).
 */
class ReviewQualityTest {

    @Test
    void aCorrectAnswerWithNoExplanationRequestIsThePerfectScore() {
        assertThat(ReviewQuality.derive(true, false)).isEqualTo(ReviewQuality.PERFECT);
    }

    @Test
    void aCorrectAnswerWhereTheExplanationWasRequestedIsWeakerThanPerfect() {
        int weaker = ReviewQuality.derive(true, true);

        assertThat(weaker).isEqualTo(ReviewQuality.CORRECT_BUT_UNSURE);
        assertThat(weaker).isLessThan(ReviewQuality.derive(true, false));
    }

    @Test
    void aWrongAnswerIsTheBottomScoreRegardlessOfTheExplanationFlag() {
        assertThat(ReviewQuality.derive(false, false)).isEqualTo(ReviewQuality.INCORRECT);
        assertThat(ReviewQuality.derive(false, true)).isEqualTo(ReviewQuality.INCORRECT);
    }
}
