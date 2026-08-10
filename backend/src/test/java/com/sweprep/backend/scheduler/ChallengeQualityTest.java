package com.sweprep.backend.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Proves the 0-5 collapse issue #8's resolution names exactly: a clean first-try pass
 * with no help and no complexity contradiction earns the top mark; any pass that needed
 * more, or a wrong/abandoned attempt, does not. Mirrors {@code ReviewQuality}'s test
 * shape for the rep-scheduler sibling.
 */
class ChallengeQualityTest {

    @Test
    void aCleanFirstTryPassWithNoHelpAndNoComplexityCheckIsPerfect() {
        assertThat(ChallengeQuality.derive(true, 1, 0, false, null))
                .isEqualTo(ChallengeQuality.PERFECT);
    }

    @Test
    void aCleanFirstTryPassWhoseComplexityClaimWasConsistentIsPerfect() {
        assertThat(ChallengeQuality.derive(true, 1, 0, false, true))
                .isEqualTo(ChallengeQuality.PERFECT);
    }

    @Test
    void aFailedOrAbandonedAttemptIsIncorrectRegardlessOfAnythingElse() {
        assertThat(ChallengeQuality.derive(false, 5, 2, true, true))
                .isEqualTo(ChallengeQuality.INCORRECT);
    }

    @Test
    void aPassThatClimbedTheHintLadderIsWeak() {
        assertThat(ChallengeQuality.derive(true, 1, 1, false, null))
                .isEqualTo(ChallengeQuality.WEAK_PASS);
    }

    @Test
    void aPassThatRevealedTheFailingCaseIsWeak() {
        assertThat(ChallengeQuality.derive(true, 1, 0, true, null))
                .isEqualTo(ChallengeQuality.WEAK_PASS);
    }

    @Test
    void aPassThatTookMoreThanOneSubmissionIsWeak() {
        assertThat(ChallengeQuality.derive(true, 3, 0, false, null))
                .isEqualTo(ChallengeQuality.WEAK_PASS);
    }

    @Test
    void aPassWhoseComplexityClaimWasContradictedIsWeakEvenWithoutAnyOtherHelp() {
        assertThat(ChallengeQuality.derive(true, 1, 0, false, false))
                .isEqualTo(ChallengeQuality.WEAK_PASS);
    }

    @Test
    void anInconclusiveComplexityMeasurementIsNeverTreatedAsAContradiction() {
        // null covers both "no complexity check at all" and "measurement was inconclusive"
        // (issue #17's honesty constraint: inconclusive is never worded as a failure).
        assertThat(ChallengeQuality.derive(true, 1, 0, false, null))
                .isEqualTo(ChallengeQuality.PERFECT);
    }
}
