package com.sweprep.backend.learned;

import static org.assertj.core.api.Assertions.assertThat;

import com.sweprep.backend.learned.LearnedState.Status;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The successive-relearning criterion (issue #38, design revision t3 section 4.1), tested
 * against the adversarial histories the ruling is defined by, with no database. "Learned"
 * must mean retrieval to criterion in each of N <em>spaced</em> sessions - so the tests it
 * has to survive are the ones a naive counter fails: three passes in a single sitting must
 * not graduate, three across properly spaced sessions must, and varying N or the gap ladder
 * must move the outcome.
 */
class LearnedCriterionTest {

    private static final LocalDate DAY_0 = LocalDate.of(2026, 1, 1);

    // Default criterion: N = 3 spaced sessions across an expanding [1, 3, 7, 14] day ladder.
    private static final LearnedProperties DEFAULT = new LearnedProperties(null, null);

    private static LocalDate day(int offset) {
        return DAY_0.plusDays(offset);
    }

    @Test
    void neverPassedIsNewAndDueCold() {
        LearnedState state = LearnedCriterion.evaluate(List.of(), DEFAULT);

        assertThat(state.status()).isEqualTo(Status.NEW);
        assertThat(state.spacedSessions()).isZero();
        assertThat(state.lastSpacedSession()).isNull();
        // No gap to wait out: a never-seen item is available cold.
        assertThat(state.nextGapDays()).isZero();
        assertThat(state.nextReviewOn()).isEmpty();
    }

    @Test
    void threeCleanPassesInOneSittingDoNotGraduate() {
        // Grinding the same item three times in a single day is one session, not three:
        // this is the specific single-session overlearning the ruling rejects.
        LearnedState state =
                LearnedCriterion.evaluate(List.of(day(0), day(0), day(0)), DEFAULT);

        assertThat(state.status()).isEqualTo(Status.LEARNING);
        assertThat(state.spacedSessions()).isEqualTo(1);
        assertThat(state.lastSpacedSession()).isEqualTo(day(0));
        // Still owes the first expanding gap before a second session can count.
        assertThat(state.nextGapDays()).isEqualTo(1);
    }

    @Test
    void threeCleanPassesInThreeSpacedSessionsGraduate() {
        // Days 0, 1, 4: gaps of 1 and 3 satisfy the [1, 3, ...] ladder exactly.
        LearnedState state =
                LearnedCriterion.evaluate(List.of(day(0), day(1), day(4)), DEFAULT);

        assertThat(state.status()).isEqualTo(Status.LEARNED);
        assertThat(state.spacedSessions()).isEqualTo(3);
        assertThat(state.lastSpacedSession()).isEqualTo(day(4));
        assertThat(state.isLearned()).isTrue();
        // Relearning cadence derives from the ladder, not an ease number: the next rung (7).
        assertThat(state.nextGapDays()).isEqualTo(7);
        assertThat(state.nextReviewOn()).contains(day(4).plusDays(7));
    }

    @Test
    void passesArrivingTooSoonDoNotAdvanceButLaterSpacedOnesStillCount() {
        // Day 1 is too soon after day 0 (needs 3 to reach the 2nd... no: needs 1 to reach
        // the 2nd, so day 1 counts). Then several same-week passes are all too soon for the
        // 2nd->3rd gap of 3, until day 5 finally clears it. A too-soon pass neither stacks
        // nor resets; the walk finds the earliest spaced subsequence.
        LearnedState state = LearnedCriterion.evaluate(
                List.of(day(0), day(1), day(2), day(3), day(5)), DEFAULT);

        assertThat(state.status()).isEqualTo(Status.LEARNED);
        assertThat(state.spacedSessions()).isEqualTo(3);
        // Session 1 = day 0, session 2 = day 1 (gap 1 >= 1), session 3 = day 5 (gap 4 >= 3);
        // days 2 and 3 were too soon after day 1 and did not count.
        assertThat(state.lastSpacedSession()).isEqualTo(day(5));
    }

    @Test
    void twoSpacedSessionsAreStillOnlyLearning() {
        LearnedState state = LearnedCriterion.evaluate(List.of(day(0), day(1)), DEFAULT);

        assertThat(state.status()).isEqualTo(Status.LEARNING);
        assertThat(state.spacedSessions()).isEqualTo(2);
        // Owes the 2nd->3rd rung (3 days) before the third session can count.
        assertThat(state.nextGapDays()).isEqualTo(3);
        assertThat(state.nextReviewOn()).contains(day(1).plusDays(3));
    }

    @Test
    void raisingNKeepsAnItemThatGraduatedAtThreeInLearning() {
        // The exact same history that graduates at N = 3 must not graduate at N = 4:
        // N is a real, outcome-changing knob.
        List<LocalDate> spaced = List.of(day(0), day(1), day(4), day(11));
        assertThat(LearnedCriterion.evaluate(spaced, new LearnedProperties(3, null)).status())
                .isEqualTo(Status.LEARNED);

        LearnedState atFour = LearnedCriterion.evaluate(spaced, new LearnedProperties(4, null));
        // Day 11 is only 7 after day 4, but the 3rd->4th rung of [1,3,7,14] is 7, so it counts.
        assertThat(atFour.status()).isEqualTo(Status.LEARNED);

        // Drop the 4th spaced pass: now N = 4 cannot be met, N = 3 still is.
        List<LocalDate> threeSpaced = List.of(day(0), day(1), day(4));
        assertThat(LearnedCriterion.evaluate(threeSpaced, new LearnedProperties(3, null)).status())
                .isEqualTo(Status.LEARNED);
        assertThat(LearnedCriterion.evaluate(threeSpaced, new LearnedProperties(4, null)).status())
                .isEqualTo(Status.LEARNING);
    }

    @Test
    void nOfOneGraduatesOnASinglePass() {
        LearnedState state =
                LearnedCriterion.evaluate(List.of(day(0)), new LearnedProperties(1, null));

        assertThat(state.status()).isEqualTo(Status.LEARNED);
        assertThat(state.spacedSessions()).isEqualTo(1);
        // Relearning interval is the first ladder rung.
        assertThat(state.nextGapDays()).isEqualTo(1);
    }

    @Test
    void aStricterGapLadderWithholdsGraduationTheLooseOneGrants() {
        // Same three days; only the ladder differs, and it flips the verdict.
        List<LocalDate> days = List.of(day(0), day(1), day(2));

        LearnedProperties loose = new LearnedProperties(3, List.of(1, 1));
        assertThat(LearnedCriterion.evaluate(days, loose).status()).isEqualTo(Status.LEARNED);

        LearnedProperties strict = new LearnedProperties(3, List.of(5, 5));
        LearnedState strictState = LearnedCriterion.evaluate(days, strict);
        assertThat(strictState.status()).isEqualTo(Status.LEARNING);
        // Only the first session counted; days 1 and 2 were far too soon for a 5-day gap.
        assertThat(strictState.spacedSessions()).isEqualTo(1);
    }

    @Test
    void duplicatesAndOutOfOrderPassesAreNormalisedToDistinctSortedDays() {
        // The service hands over one entry per passing submission, unordered and with
        // same-day repeats; the criterion must collapse them to distinct sorted days.
        List<LocalDate> messy = List.of(day(4), day(0), day(1), day(0), day(4), day(1));
        LearnedState state = LearnedCriterion.evaluate(messy, DEFAULT);

        assertThat(state.status()).isEqualTo(Status.LEARNED);
        assertThat(state.spacedSessions()).isEqualTo(3);
        assertThat(state.lastSpacedSession()).isEqualTo(day(4));
    }

    @Test
    void aSubDayGapIsFlooredSoSameDayPassesCannotStack() {
        // A configured gap below one day would let two passes in one sitting stack, which is
        // exactly what the criterion forbids; it is floored to one on construction.
        LearnedProperties props = new LearnedProperties(2, List.of(0));
        assertThat(props.gapLadder()).containsExactly(1);

        // Same day twice is still one session even with the "0" ladder, because 0 was floored.
        assertThat(LearnedCriterion.evaluate(List.of(day(0), day(0)), props).spacedSessions())
                .isEqualTo(1);
        // A genuine one-day gap now clears the floored rung and graduates at N = 2.
        assertThat(LearnedCriterion.evaluate(List.of(day(0), day(1)), props).status())
                .isEqualTo(Status.LEARNED);
    }

    @Test
    void aGapLadderShorterThanNClampsToItsLastRung() {
        // N = 4 but only two rungs given: the 3rd->4th gap clamps to the last (2), so a
        // history spaced by 2s graduates rather than crashing on a missing rung.
        LearnedProperties props = new LearnedProperties(4, List.of(1, 2));
        LearnedState state =
                LearnedCriterion.evaluate(List.of(day(0), day(1), day(3), day(5)), props);

        assertThat(state.status()).isEqualTo(Status.LEARNED);
        assertThat(state.spacedSessions()).isEqualTo(4);
    }
}
