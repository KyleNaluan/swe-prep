package com.sweprep.backend.complexity;

import static org.assertj.core.api.Assertions.assertThat;

import com.sweprep.backend.complexity.ComplexityClassifier.SizeTiming;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The pure classification core of empirical scaling measurement (issue #17), proven
 * against synthetic timing curves so the honesty constraint is exhaustively tested
 * without needing real compilation: a clean quadratic curve is caught, a clean linear
 * curve is not mistaken for one, and every path that cannot support a confident call -
 * too few points, too fast to time, or a slope stuck between two buckets - reports
 * {@link MeasurementOutcome.Inconclusive} rather than guessing.
 *
 * <p>The overhead-dominated regime - "real wall-clock timing of cheap code", the
 * technique's hardest case - is tested here too, and deliberately here rather than only
 * end to end: the timing numbers below are the ones a real measurement run actually
 * produced, replayed deterministically, so a regression shows up as a failing assertion
 * on a shared CI runner instead of a coin flip. The end-to-end counterparts live in
 * {@code ScalingMeasurerTest}.
 */
class ComplexityClassifierTest {

    @Test
    void aClearlyQuadraticCurveIsClassifiedAsQuadratic() {
        // time ~ size^2, scaled so the largest point clears the reliability floor.
        List<SizeTiming> points = List.of(
                new SizeTiming(1_000, 1_000_000),
                new SizeTiming(2_000, 4_000_000),
                new SizeTiming(4_000, 16_000_000),
                new SizeTiming(8_000, 64_000_000));

        MeasurementOutcome outcome = ComplexityClassifier.classify(points);

        assertThat(outcome).isInstanceOfSatisfying(MeasurementOutcome.Conclusive.class, conclusive -> {
            assertThat(conclusive.bucket()).isEqualTo(ComplexityBucket.QUADRATIC);
            assertThat(conclusive.exponent()).isCloseTo(2.0, org.assertj.core.data.Offset.offset(0.1));
        });
    }

    @Test
    void aClearlyLinearCurveIsClassifiedAsLinearNeverAsQuadratic() {
        List<SizeTiming> points = List.of(
                new SizeTiming(1_000, 1_000_000),
                new SizeTiming(2_000, 2_000_000),
                new SizeTiming(4_000, 4_000_000),
                new SizeTiming(8_000, 8_000_000));

        MeasurementOutcome outcome = ComplexityClassifier.classify(points);

        assertThat(outcome).isInstanceOfSatisfying(MeasurementOutcome.Conclusive.class,
                conclusive -> assertThat(conclusive.bucket()).isEqualTo(ComplexityBucket.LINEAR));
    }

    @Test
    void aLinearithmicCurveAlsoClassifiesAsLinearNeverFlaggedAgainstALinearClaim() {
        // n log n grows only slightly faster than n at these sizes; the honesty
        // constraint says this must land in the same bucket as pure linear, not be
        // reported as a mismatch against either an O(n) or an O(n log n) claim.
        List<SizeTiming> points = List.of(
                new SizeTiming(1_000, 1_000 * log2(1_000) * 1_000),
                new SizeTiming(2_000, 2_000 * log2(2_000) * 1_000),
                new SizeTiming(4_000, 4_000 * log2(4_000) * 1_000),
                new SizeTiming(8_000, 8_000 * log2(8_000) * 1_000));

        MeasurementOutcome outcome = ComplexityClassifier.classify(points);

        assertThat(outcome).isInstanceOfSatisfying(MeasurementOutcome.Conclusive.class,
                conclusive -> assertThat(conclusive.bucket()).isEqualTo(ComplexityBucket.LINEAR));
    }

    @Test
    void aSingleSlowOutlierAmongOtherwiseLinearSamplesDoesNotFlipTheBucket() {
        // Simulates what ScalingMeasurer's per-size fastest-of-N already guards against at the
        // repetition level: even feeding the classifier one point whose "representative"
        // time was contaminated by a one-off GC pause should not, on its own, look
        // quadratic if the surrounding trend is otherwise clean linear growth - the
        // regression's overall slope stays governed by the other three clean points.
        List<SizeTiming> points = List.of(
                new SizeTiming(1_000, 1_000_000),
                new SizeTiming(2_000, 2_050_000), // a touch of noise, not a real outlier
                new SizeTiming(4_000, 4_000_000),
                new SizeTiming(8_000, 8_000_000));

        MeasurementOutcome outcome = ComplexityClassifier.classify(points);

        assertThat(outcome).isInstanceOfSatisfying(MeasurementOutcome.Conclusive.class,
                conclusive -> assertThat(conclusive.bucket()).isEqualTo(ComplexityBucket.LINEAR));
    }

    @Test
    void tooFewPointsIsInconclusive() {
        MeasurementOutcome outcome =
                ComplexityClassifier.classify(List.of(new SizeTiming(1_000, 1_000_000)));

        assertThat(outcome).isInstanceOf(MeasurementOutcome.Inconclusive.class);
    }

    @Test
    void tooFastToMeasureIsInconclusiveNeverAssertedAsConstant() {
        // Every point is well under the reliability floor - both a constant-time and a
        // linear solution look identical at these magnitudes, so nothing may be asserted.
        List<SizeTiming> points = List.of(
                new SizeTiming(1_000, 500),
                new SizeTiming(2_000, 600),
                new SizeTiming(4_000, 700));

        MeasurementOutcome outcome = ComplexityClassifier.classify(points);

        assertThat(outcome).isInstanceOf(MeasurementOutcome.Inconclusive.class);
    }

    @Test
    void aSlopeStuckBetweenTwoBucketsIsInconclusiveRatherThanForcedIntoOne() {
        // Roughly size^1.5: squarely between LINEAR and QUADRATIC, inside the margin.
        List<SizeTiming> points = List.of(
                new SizeTiming(1_000, 1_000_000),
                new SizeTiming(4_000, 8_000_000),
                new SizeTiming(16_000, 64_000_000));

        MeasurementOutcome outcome = ComplexityClassifier.classify(points);

        assertThat(outcome).isInstanceOf(MeasurementOutcome.Inconclusive.class);
    }


    // --- The overhead-dominated regime: cheap code, real recorded timings ------------

    @Test
    void theRecordedCurveOfARealBfsUnderTooLittleWarmUpIsInconclusiveNeverAConfidentSublinear() {
        // The exact defect this gate exists for. These are real medians measured from the
        // reference solution of binary-tree-level-order-traversal - a textbook
        // unconditional BFS, unambiguously O(n) - when each size was warmed up by a fixed
        // count of calls rather than for a fixed time. Larger sizes ran proportionally
        // more JIT-compiled code, which cancelled the real growth out and left a fitted
        // slope near zero. Fitting them still yields that slope; what must never happen
        // again is naming a bucket for it, because SUBLINEAR would be a confident lie
        // about linear code.
        List<SizeTiming> points = List.of(
                new SizeTiming(4_000, 341_127),
                new SizeTiming(8_000, 313_877),
                new SizeTiming(16_000, 328_157),
                new SizeTiming(32_000, 617_102));

        MeasurementOutcome outcome = ComplexityClassifier.classify(points);

        assertThat(outcome).isInstanceOf(MeasurementOutcome.Inconclusive.class);
    }

    @Test
    void theRecordedCurveOfTheSameBfsWithARealWarmUpIsClassifiedAsLinear() {
        // The same solution, same sizes, after the fix: warm-up bounded by time rather
        // than by call count, and the sub-floor small sizes grown past instead of fitted.
        // A clean factor of two per doubling, and the verdict the content's authored
        // target says it should have had all along.
        List<SizeTiming> points = List.of(
                new SizeTiming(16_000, 105_972),
                new SizeTiming(32_000, 192_054),
                new SizeTiming(64_000, 451_109),
                new SizeTiming(128_000, 780_345));

        MeasurementOutcome outcome = ComplexityClassifier.classify(points);

        assertThat(outcome).isInstanceOfSatisfying(MeasurementOutcome.Conclusive.class,
                conclusive -> assertThat(conclusive.bucket()).isEqualTo(ComplexityBucket.LINEAR));
    }

    @Test
    void theRecordedCurveOfARealMonotonicStackIsClassifiedAsLinear() {
        // daily-temperatures' reference solution, the other failure the ledger named:
        // real medians at the sizes the adaptive ladder settles on for it.
        List<SizeTiming> points = List.of(
                new SizeTiming(16_000, 166_683),
                new SizeTiming(32_000, 344_407),
                new SizeTiming(64_000, 676_964),
                new SizeTiming(128_000, 1_369_278));

        MeasurementOutcome outcome = ComplexityClassifier.classify(points);

        assertThat(outcome).isInstanceOfSatisfying(MeasurementOutcome.Conclusive.class,
                conclusive -> assertThat(conclusive.bucket()).isEqualTo(ComplexityBucket.LINEAR));
    }

    @Test
    void anOverheadOnlySubmissionsScatterIsInconclusiveNeverASublinearVerdictOnNoise() {
        // Real medians from an O(1) submission (it returns the input's length). Every
        // point is a few hundred nanoseconds - the fixed per-call cost and nothing else -
        // and they wander up and down with input size purely as noise. A slope fitted
        // through them is meaningless whichever direction it points.
        List<SizeTiming> points = List.of(
                new SizeTiming(4_000, 220),
                new SizeTiming(8_000, 550),
                new SizeTiming(16_000, 310),
                new SizeTiming(32_000, 470),
                new SizeTiming(64_000, 480),
                new SizeTiming(128_000, 580));

        MeasurementOutcome outcome = ComplexityClassifier.classify(points);

        assertThat(outcome).isInstanceOf(MeasurementOutcome.Inconclusive.class);
    }

    @Test
    void aGenuinelyConstantSubmissionWithRealWorkIsClassifiedAsSublinearNotRefusedAsNoise() {
        // The mirror of the test above, and why the refusal has to be about signal rather
        // than about flatness: real medians from a submission doing a fixed 200 000
        // iterations regardless of input size. Flat, but flat well clear of the noise
        // floor and tightly so - that is a measurement, and CONSTANT lives in SUBLINEAR.
        List<SizeTiming> points = List.of(
                new SizeTiming(4_000, 115_602),
                new SizeTiming(8_000, 113_782),
                new SizeTiming(16_000, 113_473),
                new SizeTiming(32_000, 112_532));

        MeasurementOutcome outcome = ComplexityClassifier.classify(points);

        assertThat(outcome).isInstanceOfSatisfying(MeasurementOutcome.Conclusive.class,
                conclusive -> assertThat(conclusive.bucket()).isEqualTo(ComplexityBucket.SUBLINEAR));
    }

    @Test
    void pointsBelowTheReliabilityFloorAreDroppedRatherThanBendingTheFit() {
        // A cheap linear submission measured across a ladder that starts below the floor:
        // the two smallest points are in the fixed-cost/cache-resident regime and read far
        // too fast for their size. Fitting them in would push the slope toward the
        // LINEAR/QUADRATIC boundary; dropping them leaves the clean tail behind.
        List<SizeTiming> withCheapTail = List.of(
                new SizeTiming(4_000, 17_610),
                new SizeTiming(8_000, 34_390),
                new SizeTiming(16_000, 105_972),
                new SizeTiming(32_000, 192_054),
                new SizeTiming(64_000, 451_109),
                new SizeTiming(128_000, 780_345));

        MeasurementOutcome outcome = ComplexityClassifier.classify(withCheapTail);

        assertThat(ComplexityClassifier.isReliable(withCheapTail.get(0))).isFalse();
        assertThat(ComplexityClassifier.isReliable(withCheapTail.get(2))).isTrue();
        assertThat(outcome).isInstanceOfSatisfying(MeasurementOutcome.Conclusive.class,
                conclusive -> assertThat(conclusive.bucket()).isEqualTo(ComplexityBucket.LINEAR));
    }

    @Test
    void twoReliablePointsAreNeverEnoughHoweverPerfectlyTheyLine() {
        // A two-point fit has zero residual by construction, so its slope carries no
        // evidence about its own reliability. Refusing it is what stops the confidence
        // gate being trivially satisfiable.
        List<SizeTiming> points = List.of(
                new SizeTiming(4_000, 1_000_000),
                new SizeTiming(8_000, 2_000_000));

        MeasurementOutcome outcome = ComplexityClassifier.classify(points);

        assertThat(outcome).isInstanceOf(MeasurementOutcome.Inconclusive.class);
    }

    @Test
    void scatterAroundACleanBucketCentreStillRefusesRatherThanNamingIt() {
        // Points that average out to a slope of about 1 but disagree wildly with each
        // other. The fitted slope alone would say LINEAR; the interval built from their
        // own residuals is far too wide to exclude either neighbouring boundary, so no
        // bucket is named. This is the property that makes a confidently wrong verdict
        // structurally impossible rather than merely unlikely.
        List<SizeTiming> points = List.of(
                new SizeTiming(4_000, 1_000_000),
                new SizeTiming(8_000, 8_000_000),
                new SizeTiming(16_000, 2_000_000),
                new SizeTiming(32_000, 8_000_000));

        MeasurementOutcome outcome = ComplexityClassifier.classify(points);

        assertThat(outcome).isInstanceOf(MeasurementOutcome.Inconclusive.class);
    }

    private static double log2(int n) {
        return Math.log(n) / Math.log(2);
    }
}
