package com.sweprep.backend.advisor;

import static org.assertj.core.api.Assertions.assertThat;

import com.sweprep.backend.advisor.WeakSpotFixtures.WeakSpot;
import com.sweprep.backend.complexity.ComplexityBucket;
import com.sweprep.backend.exercise.Complexity;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The pure three-way comparison at the center of the LLM complexity second opinion
 * (issue #83), exercised with no network call at all. Covers plain agreement and
 * disagreement, bucket-level equivalence (a claim and a model reading that only
 * differ within one {@link ComplexityBucket} is not a disagreement), a skipped or
 * inconclusive measurement counting as an absent voice rather than a dissenting one,
 * and every named weak-spot fixture (amortised analysis, memoised recursion, hidden
 * library costs) actually producing a disagreement when the model misreads it.
 */
class ComplexityDisagreementTest {

    @Test
    void whenEveryVoiceAgreesTheResultIsQuietConfirmation() {
        ComplexityDisagreement result =
                ComplexityDisagreement.evaluate(Complexity.LINEAR, ComplexityBucket.LINEAR, Complexity.LINEAR);

        assertThat(result.agreement()).isTrue();
        assertThat(result.prompt()).isNull();
    }

    @Test
    void agreementIsAtBucketGranularityNotTheFinerComplexityVocabulary() {
        // LINEAR and LINEARITHMIC share a bucket (empirical measurement can never tell
        // them apart) - a claim/model pair that only differs within one bucket is not
        // treated as a disagreement, matching claimComplexity's own honesty constraint.
        ComplexityDisagreement result = ComplexityDisagreement.evaluate(
                Complexity.LINEAR, ComplexityBucket.LINEAR, Complexity.LINEARITHMIC);

        assertThat(result.agreement()).isTrue();
        assertThat(result.prompt()).isNull();
    }

    @Test
    void aSkippedOrInconclusiveMeasurementIsAnAbsentVoiceNeverADissentingOne() {
        // measuredBucket null (skipped/inconclusive); claim and model agree - still
        // quiet confirmation, since there is no third voice to disagree with.
        ComplexityDisagreement result = ComplexityDisagreement.evaluate(Complexity.LINEAR, null, Complexity.LINEAR);

        assertThat(result.agreement()).isTrue();
        assertThat(result.prompt()).isNull();
    }

    @Test
    void whenTheModelDisagreesWithTheClaimAloneItStillProducesADisagreement() {
        ComplexityDisagreement result =
                ComplexityDisagreement.evaluate(Complexity.LINEAR, null, Complexity.QUADRATIC);

        assertThat(result.agreement()).isFalse();
        assertThat(result.prompt())
                .contains("You claimed O(n)")
                .contains("model reads this as O(n²)")
                .doesNotContain("measurement found");
        // Never worded as a verdict - no "correct"/"wrong"/"right answer" framing.
        assertThat(result.prompt()).doesNotContainIgnoringCase("correct")
                .doesNotContainIgnoringCase("wrong");
    }

    @Test
    void aClaimMeasurementDisagreementStillProducesAPromptEvenWhenTheModelSidesWithTheClaim() {
        // The model agreeing with the claim does not silence a genuine claim-vs-measurement
        // disagreement - any two voices differing is disagreement, regardless of which pair.
        ComplexityDisagreement result =
                ComplexityDisagreement.evaluate(Complexity.LINEAR, ComplexityBucket.QUADRATIC, Complexity.LINEAR);

        assertThat(result.agreement()).isFalse();
        assertThat(result.prompt())
                .contains("You claimed O(n)")
                .contains("measurement found O(n²)")
                .contains("model reads this as O(n)");
    }

    @Test
    void allThreeVoicesDisagreeing() {
        ComplexityDisagreement result = ComplexityDisagreement.evaluate(
                Complexity.LINEAR, ComplexityBucket.CUBIC, Complexity.QUADRATIC);

        assertThat(result.agreement()).isFalse();
        assertThat(result.prompt())
                .contains("O(n)")
                .contains("O(n³)")
                .contains("O(n²)")
                .endsWith("which is right, and why?");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("weakSpots")
    void aKnownModelWeakSpotProducesADisagreementAgainstAnAccurateClaimAndMeasurement(WeakSpot weakSpot) {
        ComplexityBucket actualBucket = ComplexityBucket.of(weakSpot.actualTime());

        ComplexityDisagreement result = ComplexityDisagreement.evaluate(
                weakSpot.actualTime(), actualBucket, weakSpot.commonModelMisreading());

        assertThat(result.agreement())
                .as("a %s misreading should register as a disagreement", weakSpot.name())
                .isFalse();
        assertThat(result.prompt()).isNotBlank();
    }

    static Stream<WeakSpot> weakSpots() {
        return WeakSpotFixtures.ALL.stream();
    }
}
