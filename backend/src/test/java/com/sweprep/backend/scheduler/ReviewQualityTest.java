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
        assertThat(ReviewQuality.derive(true, false, false)).isEqualTo(ReviewQuality.PERFECT);
    }

    @Test
    void aCorrectAnswerWhereTheExplanationWasRequestedIsWeakerThanPerfect() {
        int weaker = ReviewQuality.derive(true, true, false);

        assertThat(weaker).isEqualTo(ReviewQuality.CORRECT_BUT_UNSURE);
        assertThat(weaker).isLessThan(ReviewQuality.derive(true, false, false));
    }

    @Test
    void aWrongAnswerIsTheBottomScoreRegardlessOfTheExplanationFlag() {
        assertThat(ReviewQuality.derive(false, false, false)).isEqualTo(ReviewQuality.INCORRECT);
        assertThat(ReviewQuality.derive(false, true, false)).isEqualTo(ReviewQuality.INCORRECT);
    }

    // --- Reference-solution reveal (issue #82) ---------------------------------------

    @Test
    void aCorrectAnswerWhereTheSolutionWasSeenIsWeakerThanAskingForTheExplanation() {
        int solutionSeen = ReviewQuality.derive(true, false, true);

        assertThat(solutionSeen).isEqualTo(ReviewQuality.SOLUTION_SEEN);
        assertThat(solutionSeen).isLessThan(ReviewQuality.derive(true, true, false));
    }

    @Test
    void seeingTheSolutionTakesPrecedenceOverTheExplanationFlagWhenBothAreTrue() {
        assertThat(ReviewQuality.derive(true, true, true)).isEqualTo(ReviewQuality.SOLUTION_SEEN);
    }

    @Test
    void aWrongAnswerStaysTheBottomScoreEvenIfTheSolutionWasSeen() {
        assertThat(ReviewQuality.derive(false, false, true)).isEqualTo(ReviewQuality.INCORRECT);
    }
}
