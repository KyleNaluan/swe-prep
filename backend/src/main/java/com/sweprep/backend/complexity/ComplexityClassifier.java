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
 *
 * <p>Fitting a slope is the easy half. The hard half is refusing to name a bucket when
 * the points do not support one, and that refusal is what the three gates below exist
 * for. They are ordered cheapest-first and every one of them can only ever turn a
 * would-be {@link MeasurementOutcome.Conclusive} into an {@link
 * MeasurementOutcome.Inconclusive}, never the reverse:
 *
 * <ol>
 *   <li><b>Points above {@link #MIN_RELIABLE_NANOS}.</b> A point below it is dominated
 *       by the fixed per-call cost and by whether that input happened to fit in cache
 *       rather than by the algorithm, so it is dropped before anything is fitted.
 *   <li><b>At least {@value #MIN_POINTS} surviving points.</b> A line through two points
 *       has exactly zero residual whatever the data, so a two-point fit carries no
 *       information about its own reliability at all - it would sail through gate 3 no
 *       matter how badly the timings were corrupted. Three is the smallest count at
 *       which the fit can be checked against itself.
 *   <li><b>A slope confidence interval that stays inside one bucket.</b> The standard
 *       error of the fitted slope is computed from the points' own residuals and widened
 *       by {@link #CONFIDENCE_MULTIPLIER}; if the resulting interval touches a bucket
 *       boundary, no bucket is named. This is the gate that makes a confidently wrong
 *       verdict structurally impossible for scattered timings rather than merely
 *       unlikely: scatter enlarges the interval, and a large enough interval cannot fit
 *       between two boundaries.
 * </ol>
 *
 * <p>Gate 3 replaced a fixed margin around each boundary, which could not tell a
 * tightly-fitted slope of 0.3 (genuinely sublinear) from a wildly scattered one that
 * happened to average 0.3 - and the second of those is exactly how a textbook linear BFS
 * once measured as a confident {@code SUBLINEAR}. The fixed margin survives as a
 * <em>floor</em> on the interval's half-width ({@link #MIN_HALF_WIDTH}), so a suspiciously
 * tight fit near a boundary is still refused: measurement precision is not the same thing
 * as measurement accuracy, and "do not chase precision you cannot have" (issue #17) applies
 * to both.
 */
public final class ComplexityClassifier {

    /** One size's representative timing - already trimmed (e.g. the median of several repetitions). */
    public record SizeTiming(int size, double nanos) {}

    /**
     * Fewer points than this and the fit cannot be checked against itself: with two
     * points the residuals are identically zero, so the slope's standard error is
     * undefined (zero degrees of freedom) and the confidence gate would wave anything
     * through.
     */
    public static final int MIN_POINTS = 3;

    /**
     * The per-point floor below which a measured time says more about the machine than
     * about the algorithm, so the point is dropped rather than fitted. Measured, not
     * guessed, from two separate effects that both fade out above it:
     *
     * <ul>
     *   <li>An O(1) submission times at ~0.3-0.5 microseconds per call at every size
     *       through the real harness (the timed window excludes process start,
     *       compilation, JSON parsing and argument binding), and its per-size spread
     *       stays a large fraction of that. Points within an order of magnitude of the
     *       constant term carry no usable growth signal.
     *   <li>A small enough input is cache-resident and a larger one is not, which shows
     *       up as a knee, not as noise. Measured directly: the real BFS reference
     *       solution timed 36-83 microseconds over 4 000 nodes and 138-166 over 8 000 -
     *       roughly four times the cost for twice the input - and then a clean factor of
     *       two per doubling from 8 000 onward. Fitting the 4 000 point in bent the curve
     *       enough to make an unambiguously linear submission unclassifiable.
     * </ul>
     *
     * <p>100 microseconds sits above both regimes for every real reference solution
     * measured. The measurer's answer to a submission whose cheap end falls below it is
     * to grow the input until enough points clear it, not to fit the bad points anyway
     * (see {@code ScalingMeasurer}); the answer here, if too few clear it even then, is
     * an honest inconclusive.
     */
    public static final double MIN_RELIABLE_NANOS = 100_000; // 100 us

    // How many standard errors wide the slope's confidence interval is taken to be.
    // Three, matching the two-sided ~90% Student-t point at the two degrees of freedom
    // the default four sizes leave (t = 2.92), rounded up rather than down - the cost of
    // an over-wide interval is an honest "inconclusive", and the cost of an under-wide
    // one is the confidently wrong verdict this whole gate exists to prevent.
    private static final double CONFIDENCE_MULTIPLIER = 3.0;

    // Floor on the confidence interval's half-width. A fit can be tight purely because
    // few, evenly-spaced sizes were measured; this keeps a slope that lands within a
    // quarter of a boundary from being claimed for either neighbouring bucket however
    // small its standard error came out.
    private static final double MIN_HALF_WIDTH = 0.25;

    // Exponent boundaries between adjacent buckets: SUBLINEAR|LINEAR, LINEAR|QUADRATIC,
    // QUADRATIC|CUBIC, CUBIC|EXPONENTIAL - centred on the buckets' natural exponents
    // (~0, ~1, ~2, ~3).
    private static final double[] BOUNDARIES = {0.5, 1.5, 2.5, 3.5};

    private ComplexityClassifier() {}

    /**
     * Whether one measured point carries enough signal to be worth fitting at all - the
     * question {@code ScalingMeasurer} asks to decide whether to keep growing the input.
     */
    public static boolean isReliable(SizeTiming point) {
        return point.nanos() >= MIN_RELIABLE_NANOS;
    }

    public static MeasurementOutcome classify(List<SizeTiming> points) {
        List<SizeTiming> reliable = points.stream().filter(ComplexityClassifier::isReliable).toList();
        if (reliable.size() < MIN_POINTS) {
            return new MeasurementOutcome.Inconclusive(points.size() < MIN_POINTS
                    ? "not enough measured sizes completed to fit a trend that can be checked "
                            + "against itself"
                    : "the submission ran too fast at the measured input sizes to distinguish "
                            + "its growth rate from timing noise");
        }

        Fit fit = fit(reliable);
        double halfWidth = Math.max(MIN_HALF_WIDTH, CONFIDENCE_MULTIPLIER * fit.slopeStandardError());
        ComplexityBucket bucket = bucketFor(fit.slope(), halfWidth);
        if (bucket == null) {
            return new MeasurementOutcome.Inconclusive(
                    "the measured timings do not pin the growth rate down to a single "
                            + "complexity class - the fitted trend either sits between two "
                            + "classes or scatters too much to tell them apart");
        }
        return new MeasurementOutcome.Conclusive(bucket, fit.slope());
    }

    /**
     * A least-squares fit of ln(nanos) against ln(size), with the standard error of the
     * slope estimated from the fit's own residuals - the usual
     * {@code sqrt(residualSumOfSquares / ((n - 2) * sumOfSquaredXDeviations))}. Points
     * that sit on a straight line give a standard error near zero; scattered ones give a
     * large one, which is what {@link #classify} turns into a refusal to name a bucket.
     */
    private record Fit(double slope, double slopeStandardError) {}

    private static Fit fit(List<SizeTiming> points) {
        int n = points.size();
        double sumX = 0;
        double sumY = 0;
        for (SizeTiming point : points) {
            sumX += Math.log(point.size());
            sumY += Math.log(point.nanos());
        }
        double meanX = sumX / n;
        double meanY = sumY / n;

        double sumXDeviationSquared = 0;
        double sumXyDeviation = 0;
        for (SizeTiming point : points) {
            double dx = Math.log(point.size()) - meanX;
            sumXDeviationSquared += dx * dx;
            sumXyDeviation += dx * (Math.log(point.nanos()) - meanY);
        }
        if (sumXDeviationSquared == 0) {
            // Every measured size was the same size - no trend to fit. Reported as a
            // maximally uncertain slope so classify() refuses it at the interval gate.
            return new Fit(0, Double.POSITIVE_INFINITY);
        }
        double slope = sumXyDeviation / sumXDeviationSquared;

        double residualSumOfSquares = 0;
        for (SizeTiming point : points) {
            double predicted = meanY + slope * (Math.log(point.size()) - meanX);
            double residual = Math.log(point.nanos()) - predicted;
            residualSumOfSquares += residual * residual;
        }
        double standardError = Math.sqrt(residualSumOfSquares / ((n - 2) * sumXDeviationSquared));
        return new Fit(slope, standardError);
    }

    /**
     * The bucket the interval {@code slope ± halfWidth} lies wholly within, or {@code
     * null} when that interval straddles a boundary (or is not a finite interval at all).
     */
    private static ComplexityBucket bucketFor(double slope, double halfWidth) {
        if (!Double.isFinite(slope) || !Double.isFinite(halfWidth)) {
            return null;
        }
        for (double boundary : BOUNDARIES) {
            if (Math.abs(slope - boundary) < halfWidth) {
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
