package com.sweprep.backend.advisor;

import static org.assertj.core.api.Assertions.assertThat;

import com.sweprep.backend.advisor.WeakSpotFixtures.WeakSpot;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pure string assembly, no network - a change to the prompt template shows up as a
 * plain diff in these assertions rather than a silent behavior change (issue #83's
 * "regressions in prompt or model choice are visible" acceptance criterion).
 */
class ComplexityAdvisorPromptTest {

    @Test
    void includesTheProblemAndTheSubmissionSource() {
        WeakSpot fixture = WeakSpotFixtures.STRING_CONCAT_IN_LOOP;

        String prompt = ComplexityAdvisorPrompt.build(fixture.exercise(), fixture.submissionSource(), "java");

        assertThat(prompt)
                .contains(fixture.exercise().title())
                .contains(fixture.exercise().statement())
                .contains(fixture.submissionSource())
                .contains("java");
    }

    @Test
    void asksForBothTheReadingAndTheReasoning() {
        WeakSpot fixture = WeakSpotFixtures.MEMOISED_RECURSION;

        String prompt = ComplexityAdvisorPrompt.build(fixture.exercise(), fixture.submissionSource(), "java");

        // The reasoning is what the disagreement prompt shows the learner (issue #83),
        // so the request must never ask for the bucket alone.
        assertThat(prompt).containsIgnoringCase("complexity").containsIgnoringCase("reasoning");
    }

    @Test
    void neverLeaksTheSolversClaimOrTheMeasuredResult() {
        // The model must form an independent reading; steering it toward an already-known
        // value would defeat the point of a second opinion.
        WeakSpot fixture = WeakSpotFixtures.AMORTISED_DOUBLING;

        String prompt = ComplexityAdvisorPrompt.build(fixture.exercise(), fixture.submissionSource(), "java");

        assertThat(prompt).doesNotContainIgnoringCase("claim").doesNotContainIgnoringCase("measured");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("weakSpots")
    void promptsTheModelToWatchForEveryNamedWeakSpotCategory(WeakSpot weakSpot) {
        String prompt = ComplexityAdvisorPrompt.build(weakSpot.exercise(), weakSpot.submissionSource(), "java");

        // issue #83 names these three weak-spot categories explicitly; the prompt should
        // steer the model toward all of them regardless of which one this fixture is.
        assertThat(prompt)
                .containsIgnoringCase("amortised")
                .containsIgnoringCase("memoised")
                .contains("List.contains")
                .contains("substring");
    }

    static Stream<WeakSpot> weakSpots() {
        return WeakSpotFixtures.ALL.stream();
    }
}
