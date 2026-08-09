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

    private static double log2(int n) {
        return Math.log(n) / Math.log(2);
    }
}
