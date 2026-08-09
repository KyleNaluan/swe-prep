package com.sweprep.backend.complexity;

import java.util.List;

/**
 * Turns a handful of (size, representative time) points into a coarse {@link
 * MeasurementOutcome}, by fitting a log-log slope: for a cost that truly scales as
 * {@code time ~ size^p}, plotting {@code ln(time)} against {@code ln(size)} lines up
 * on a straight line of slope {@code p}, so a least-squares fit of the log-log points
 * estimates the polynomial degree without ever needing to know the constant factor
 * (issue #17). Pure and free of the runner/DB, so the honesty constraint - inconclusive
 * is a first-class outcome, never an else-branch reached only by accident - is
 * exhaustively unit-tested against synthetic curves, independent of real compilation.
 */
public final class ComplexityClassifier {

    /** One size's representative timing - already trimmed (e.g. the fastest of several repetitions). */
    public record SizeTiming(int size, double nanos) {}

    // A fit whose slope sits within this margin of a bucket boundary is not confidently
    // in either neighbouring bucket. "Do not chase precision you cannot have" (issue #17):
    // this margin is what keeps a borderline fit from being forced into a bucket at all.
    private static final double BOUNDARY_MARGIN = 0.25;

    // Exponent boundaries between adjacent buckets: SUBLINEAR|LINEAR, LINEAR|QUADRATIC,
    // QUADRATIC|CUBIC, CUBIC|EXPONENTIAL - centred on the buckets' natural exponents
    // (~0, ~1, ~2, ~3).
    private static final double[] BOUNDARIES = {0.5, 1.5, 2.5, 3.5};

    // A representative time below this is too close to timer resolution and JVM/JIT noise
    // to trust at all: both a constant-time and a linear solution look identical at these
    // magnitudes, so no slope computed from them is trustworthy.
    private static final double MIN_RELIABLE_NANOS = 200_000; // 0.2 ms

    private ComplexityClassifier() {}

    public static MeasurementOutcome classify(List<SizeTiming> points) {
        if (points.size() < 2) {
            return new MeasurementOutcome.Inconclusive(
                    "not enough measured sizes completed to fit a trend");
        }
        double largest = points.stream().mapToDouble(SizeTiming::nanos).max().orElse(0);
        if (largest < MIN_RELIABLE_NANOS) {
            return new MeasurementOutcome.Inconclusive(
                    "the submission ran too fast at every measured size to distinguish its "
                            + "growth rate from timing noise");
        }

        double slope = logLogSlope(points);
        ComplexityBucket bucket = bucketFor(slope);
        if (bucket == null) {
            return new MeasurementOutcome.Inconclusive(
                    "measured growth sits between two complexity classes and cannot be "
                            + "confidently classified");
        }
        return new MeasurementOutcome.Conclusive(bucket, slope);
    }

    /** Least-squares slope of ln(nanos) against ln(size) across the measured points. */
    private static double logLogSlope(List<SizeTiming> points) {
        int n = points.size();
        double sumX = 0;
        double sumY = 0;
        double sumXY = 0;
        double sumXX = 0;
        for (SizeTiming point : points) {
            double x = Math.log(point.size());
            double y = Math.log(point.nanos());
            sumX += x;
            sumY += y;
            sumXY += x * y;
            sumXX += x * x;
        }
        double meanX = sumX / n;
        double meanY = sumY / n;
        double numerator = sumXY - n * meanX * meanY;
        double denominator = sumXX - n * meanX * meanX;
        return denominator == 0 ? 0 : numerator / denominator;
    }

    /** The bucket {@code slope} confidently lands in, or {@code null} inside a boundary margin. */
    private static ComplexityBucket bucketFor(double slope) {
        for (double boundary : BOUNDARIES) {
            if (Math.abs(slope - boundary) < BOUNDARY_MARGIN) {
                return null;
            }
        }
        if (slope < BOUNDARIES[0]) {
            return ComplexityBucket.SUBLINEAR;
        }
        if (slope < BOUNDARIES[1]) {
            return ComplexityBucket.LINEAR;
        }
        if (slope < BOUNDARIES[2]) {
            return ComplexityBucket.QUADRATIC;
        }
        if (slope < BOUNDARIES[3]) {
            return ComplexityBucket.CUBIC;
        }
        return ComplexityBucket.EXPONENTIAL;
    }
}
