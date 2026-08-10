package com.sweprep.backend.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

/**
 * Proves the priority score against every acceptance criterion of issue #21, one test per
 * criterion where the criterion is a single, isolatable rule. Pure, like {@code Sm2SchedulerTest}
 * and {@code LearnedCriterionTest} - no database, since {@link ChallengePriority} takes
 * nothing but already-reduced {@link ChallengeCandidate}s.
 */
class ChallengePriorityTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 1, 10);

    private static ChallengeSchedulerProperties defaults() {
        return new ChallengeSchedulerProperties(null, null, null, null, null, null, null);
    }

    // -- "A problem attempted within the minimum interval is never selected" --------------

    @Test
    void aChallengeAttemptedExactlyAtTheFloorIsExcluded() {
        // Default floor is 2 days - "resolving something from two days ago is memorisation".
        ChallengeCandidate candidate =
                new ChallengeCandidate("only", List.of(new Review(TODAY.minusDays(2), ChallengeQuality.PERFECT)), 1.0);

        OptionalDouble score = ChallengePriority.scoreOf(candidate, TODAY, defaults(), 0);

        assertThat(score).isEmpty();
        assertThat(ChallengePriority.select(List.of(candidate), TODAY, defaults(), 0)).isEmpty();
    }

    @Test
    void aChallengeAttemptedJustPastTheFloorIsEligible() {
        ChallengeCandidate candidate =
                new ChallengeCandidate("only", List.of(new Review(TODAY.minusDays(3), ChallengeQuality.PERFECT)), 1.0);

        assertThat(ChallengePriority.scoreOf(candidate, TODAY, defaults(), 0)).isPresent();
    }

    // -- "A genuinely weak due problem takes precedence over introducing a new one" -------

    @Test
    void aWeakOldChallengeOutscoresANeverAttemptedOne() {
        ChallengeCandidate weak = new ChallengeCandidate(
                "weak", List.of(new Review(TODAY.minusDays(10), ChallengeQuality.INCORRECT)), 1.0);
        ChallengeCandidate fresh = new ChallengeCandidate("new", List.of(), 1.0);

        Optional<String> chosen = ChallengePriority.select(List.of(fresh, weak), TODAY, defaults(), 0);

        assertThat(chosen).contains("weak");
    }

    // -- "New-problem introductions are capped per week" -----------------------------------

    @Test
    void aNeverAttemptedChallengeIsEligibleUnderTheWeeklyCap() {
        ChallengeCandidate fresh = new ChallengeCandidate("new", List.of(), 1.0);

        // Default cap is 3; two introductions already this week leaves room for one more.
        assertThat(ChallengePriority.scoreOf(fresh, TODAY, defaults(), 2)).isPresent();
    }

    @Test
    void aNeverAttemptedChallengeIsExcludedOnceTheWeeklyCapIsReached() {
        ChallengeCandidate fresh = new ChallengeCandidate("new", List.of(), 1.0);

        assertThat(ChallengePriority.scoreOf(fresh, TODAY, defaults(), 3)).isEmpty();
        assertThat(ChallengePriority.select(List.of(fresh), TODAY, defaults(), 3)).isEmpty();
    }

    // -- "A repeatedly-passed problem stops appearing without being deleted" --------------

    @Test
    void aChallengePassedCleanlyThreeTimesStopsOutscoringAFreshCandidate() {
        ChallengeCandidate retired = new ChallengeCandidate(
                "retired",
                List.of(
                        new Review(TODAY.minusDays(60), ChallengeQuality.PERFECT),
                        new Review(TODAY.minusDays(40), ChallengeQuality.PERFECT),
                        new Review(TODAY.minusDays(20), ChallengeQuality.PERFECT)),
                1.0);
        ChallengeCandidate fresh = new ChallengeCandidate("new", List.of(), 1.0);

        Optional<String> chosen = ChallengePriority.select(List.of(retired, fresh), TODAY, defaults(), 0);

        assertThat(chosen).contains("new");
    }

    @Test
    void aRetiredChallengeIsStillSelectableWhenNothingElseIsEligible() {
        // Never deleted: with no competing candidate it is still the answer, just a
        // deeply unattractive one once genuinely mastered.
        ChallengeCandidate retired = new ChallengeCandidate(
                "retired",
                List.of(
                        new Review(TODAY.minusDays(60), ChallengeQuality.PERFECT),
                        new Review(TODAY.minusDays(40), ChallengeQuality.PERFECT),
                        new Review(TODAY.minusDays(20), ChallengeQuality.PERFECT)),
                1.0);

        assertThat(ChallengePriority.select(List.of(retired), TODAY, defaults(), 0)).contains("retired");
    }

    // -- "The daily main exercise is selected by score rather than by a due date" ---------

    @Test
    void theHighestScoringEligibleCandidateWins() {
        ChallengeCandidate barelyOverdue = new ChallengeCandidate(
                "barely", List.of(new Review(TODAY.minusDays(4), ChallengeQuality.PERFECT)), 1.0);
        ChallengeCandidate veryWeak = new ChallengeCandidate(
                "veryWeak", List.of(new Review(TODAY.minusDays(4), ChallengeQuality.INCORRECT)), 1.0);

        assertThat(ChallengePriority.select(List.of(barelyOverdue, veryWeak), TODAY, defaults(), 0))
                .contains("veryWeak");
    }

    // -- topic coverage: an under-covered topic raises priority ----------------------------

    @Test
    void lowerTopicCoverageOutscoresFullCoverageAllElseEqual() {
        ChallengeCandidate underCovered = new ChallengeCandidate("under", List.of(), 0.0);
        ChallengeCandidate fullyCovered = new ChallengeCandidate("full", List.of(), 1.0);

        assertThat(ChallengePriority.select(List.of(fullyCovered, underCovered), TODAY, defaults(), 0))
                .contains("under");
    }

    @Test
    void anEmptyCandidateListSelectsNothing() {
        assertThat(ChallengePriority.select(List.of(), TODAY, defaults(), 0)).isEmpty();
    }
}
