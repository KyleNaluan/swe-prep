package com.sweprep.backend.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.sweprep.backend.attempt.CurrentUser;
import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.Response;
import com.sweprep.backend.grader.AnswerKeyGrader;
import com.sweprep.backend.grader.Verdict;
import com.sweprep.backend.testsupport.Fixtures;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The presentation-order shuffle (issue #59). Options are re-ordered at serve time so a
 * learner cannot exploit answer position - in the first authored AI/ML batch the correct
 * answer was option 1 in all 12 checks. The production order is seeded on (exercise id,
 * user, calendar day): it must be a permutation (nothing added or dropped), <em>stable
 * within a day</em> so it never reshuffles under the learner on a refresh or resume, yet
 * <em>rotating between days</em> so spaced-repetition exposures do not teach a fixed slot.
 * The seam is injectable so the shipped, deterministic path is the tested one rather than a
 * shuffle switched off in test config.
 */
class DeterministicOptionShufflerTest {

    private static final CurrentUser USER = new CurrentUser();
    private static final List<String> FOUR =
            List.of("Two pointers", "Sliding window", "Binary search", "Hash set");

    private static Clock on(String isoDate) {
        return Clock.fixed(
                LocalDate.parse(isoDate).atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
    }

    private static DeterministicOptionShuffler shufflerOn(String isoDate) {
        return new DeterministicOptionShuffler(on(isoDate), USER);
    }

    @Test
    void producesAPermutationOfTheInput() {
        assertThat(shufflerOn("2026-08-08").order("some-id", FOUR))
                .containsExactlyInAnyOrderElementsOf(FOUR)
                .hasSameSizeAs(FOUR);
    }

    @Test
    void isStableWithinADaySoItNeverReshufflesUnderTheLearner() {
        // Two fetches on the same day - the first render, a refresh, a resumed session, a
        // re-queued rep - return the identical order.
        DeterministicOptionShuffler a = shufflerOn("2026-08-08");
        DeterministicOptionShuffler b = shufflerOn("2026-08-08");
        assertThat(a.order("check-1", FOUR)).isEqualTo(b.order("check-1", FOUR));
    }

    @Test
    void rotatesBetweenDaysSoSpacedRepetitionDoesNotTeachAFixedSlot() {
        // The same check on two different calendar days is presented in different orders, so
        // meeting it repeatedly cannot teach "the answer is slot N".
        List<String> dayOne = shufflerOn("2026-08-08").order("rep-pattern-id", FOUR);
        List<String> dayTwo = shufflerOn("2026-08-09").order("rep-pattern-id", FOUR);
        assertThat(dayOne).isNotEqualTo(dayTwo);
    }

    @Test
    void doesNotFixTheAuthoredFirstKeyInTheFirstSlotAcrossDays() {
        // rep-pattern-id authors the correct answer ("Two pointers") first. It is not pinned
        // to the first slot: on this day the shuffler moves it off, and the first option even
        // differs across days - so "always pick option 1" never works.
        List<String> dayOne = shufflerOn("2026-08-08").order("rep-pattern-id", FOUR);
        List<String> dayTwo = shufflerOn("2026-08-09").order("rep-pattern-id", FOUR);
        assertThat(dayOne).first().isNotEqualTo("Two pointers");
        assertThat(dayOne).isNotEqualTo(FOUR);
        assertThat(dayOne.get(0)).isNotEqualTo(dayTwo.get(0));
    }

    @Test
    void differentChecksGetDifferentOrders() {
        DeterministicOptionShuffler shuffler = shufflerOn("2026-08-08");
        // Keying on the id (not one global order) is what scatters the key across slots
        // batch-wide, so "always pick option 1" stops working.
        assertThat(shuffler.order("check-a", FOUR)).isNotEqualTo(shuffler.order("check-b", FOUR));
    }

    @Test
    void differentUsersGetDifferentOrdersSoNoSharedPositionMap() {
        CurrentUser other = new CurrentUser() {
            @Override
            public java.util.UUID id() {
                return java.util.UUID.fromString("00000000-0000-0000-0000-0000000000ff");
            }
        };
        DeterministicOptionShuffler mine = new DeterministicOptionShuffler(on("2026-08-08"), USER);
        DeterministicOptionShuffler theirs = new DeterministicOptionShuffler(on("2026-08-08"), other);
        assertThat(mine.order("check-1", FOUR)).isNotEqualTo(theirs.order("check-1", FOUR));
    }

    @Test
    void theInjectedSeedControlsTheOrderExactly() {
        // The seed function is the seam: a fixed seed yields a known permutation, with no
        // need to disable the shuffle in test config.
        DeterministicOptionShuffler seeded =
                new DeterministicOptionShuffler(on("2026-08-08"), USER, key -> 3L);
        assertThat(seeded.order("ignored", FOUR))
                .containsExactly("Sliding window", "Two pointers", "Hash set", "Binary search");
    }

    @Test
    void gradingIsUnaffectedByPresentationOrderBecauseTheKeyMatchesByText() {
        // The shuffle changes only what the editor renders; grading matches the submitted
        // option text against the answer key, never a position. So the correct option grades
        // PASSED wherever the shuffle places it, and a distractor grades FAILED.
        Exercise concept = Fixtures.concept();
        AnswerKeyGrader grader = new AnswerKeyGrader(Fixtures.MAPPER);
        List<String> served = shufflerOn("2026-08-08")
                .order(concept.id(), ((Response.Choice) concept.response()).optionTexts());

        for (String option : served) {
            Verdict verdict = grader.grade(concept, option);
            Verdict.Outcome expected =
                    option.equals("B") ? Verdict.Outcome.PASSED : Verdict.Outcome.FAILED;
            assertThat(verdict.outcome())
                    .as("option %s served at position %d", option, served.indexOf(option))
                    .isEqualTo(expected);
        }
    }
}
