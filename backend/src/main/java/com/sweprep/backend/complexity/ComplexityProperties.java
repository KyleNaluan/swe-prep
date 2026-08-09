package com.sweprep.backend.complexity;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How empirical scaling measurement is shaped (issue #17): the input sizes measured,
 * ascending, how many untimed warm-up calls precede the timed ones at each size, and
 * how many timed repetitions are taken.
 *
 * <p>Both the sizes and the warm-up count were settled empirically (see {@code
 * ScalingMeasurerTest}), not just calculated - real execution surfaced two distinct
 * noise sources a napkin estimate missed entirely:
 *
 * <ul>
 *   <li><b>JIT warm-up.</b> A submission cheap enough to auto-vectorize (a plain array
 *       sum, say) can show its first few calls an order of magnitude slower than later
 *       ones purely because the JIT has not yet compiled or vectorised the hot loop -
 *       nothing to do with the algorithm's growth rate. {@link #warmupRepetitions}
 *       untimed calls are made and discarded before any timed sample is taken, so this
 *       transition never contaminates a measurement.
 *   <li><b>Shared-box contention.</b> A GC pause or another process getting the CPU
 *       adds delay to individual repetitions unpredictably. The measurer's median
 *       across {@link #repetitions} timed calls is what keeps one such outlier from
 *       shifting a size's representative time on its own.
 * </ul>
 *
 * <p>The sizes ({@code [4000, 8000, 16000, 32000]}) need to be small enough for a
 * genuinely quadratic submission - the case this check exists to catch - to still
 * finish every size comfortably inside the shared {@code sweprep.grader.timeout}
 * (default 10s), but large enough that even a cheap O(n) op runs long enough,
 * post-warm-up, to clear the classifier's timing-noise floor. This is why
 * growing-input measurement never needed its own, looser timeout - the sizes are
 * chosen to respect the existing one (see the project notes on the runner's
 * execution guarantees), not the other way around.
 *
 * @param sizes             measured input sizes, ascending (sorted defensively if not)
 * @param warmupRepetitions untimed calls made and discarded before timing starts,
 *                          at each size
 * @param repetitions       timed calls made at each size, after warm-up; the measurer
 *                          takes their median
 */
@ConfigurationProperties(prefix = "sweprep.complexity")
public record ComplexityProperties(List<Integer> sizes, Integer warmupRepetitions, Integer repetitions) {

    private static final List<Integer> DEFAULT_SIZES = List.of(4_000, 8_000, 16_000, 32_000);
    private static final int DEFAULT_WARMUP_REPETITIONS = 5;
    private static final int DEFAULT_REPETITIONS = 9;

    public ComplexityProperties {
        sizes = (sizes == null || sizes.isEmpty()) ? DEFAULT_SIZES : sizes.stream().sorted().toList();
        warmupRepetitions = (warmupRepetitions == null || warmupRepetitions < 0)
                ? DEFAULT_WARMUP_REPETITIONS
                : warmupRepetitions;
        repetitions = (repetitions == null || repetitions < 1) ? DEFAULT_REPETITIONS : repetitions;
    }
}
