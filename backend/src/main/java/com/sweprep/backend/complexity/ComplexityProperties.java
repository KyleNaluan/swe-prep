package com.sweprep.backend.complexity;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How empirical scaling measurement is shaped (issue #17): which input sizes are
 * measured, how long each size is warmed up before any timed call, how many timed
 * repetitions are taken, and the ceiling on total measurement work.
 *
 * <p>Every default here was settled by direct measurement, not calculation - see
 * {@code ScalingMeasurerTest} for the end-to-end proofs and {@code
 * ComplexityClassifierTest} for the deterministic ones. What that measurement found,
 * and what each knob therefore exists to control:
 *
 * <h2>The failure this shape exists to prevent</h2>
 *
 * <p>Cheap-but-genuinely-linear submissions used to measure as either inconclusive or,
 * worse, a confidently wrong {@code SUBLINEAR}: a textbook BFS measured a fitted
 * exponent of 0.12-0.19 instead of ~1.0. Measuring the pieces directly settled where
 * that came from, and ruled out the obvious suspect:
 *
 * <ul>
 *   <li><b>Not fixed per-call overhead.</b> An O(1) submission measures ~0.3-0.5 µs per
 *       call at every size, four orders of magnitude below the 0.1-1 ms a linear
 *       submission takes at these sizes. Process start, compilation, JSON parsing and
 *       argument binding all sit <em>outside</em> the timed window by construction (see
 *       {@code LanguageAdapter#generateTimingHarness}), so there is no meaningful
 *       constant term to subtract and no intercept worth fitting. Baseline subtraction
 *       was measured and rejected on that evidence rather than assumed away.
 *   <li><b>Incomplete, size-dependent JIT warm-up.</b> A fixed <em>count</em> of warm-up
 *       calls does not reach a steady state at the same point for every size, because
 *       the JIT compiles on invocation <em>and</em> loop back-edge counters: five calls
 *       over a 4 000-element input rack up far fewer back-edges than five calls over a
 *       32 000-element one, so the larger sizes ran proportionally more compiled code.
 *       That is a speed-up that grows with input size - exactly the shape that cancels
 *       out real linear growth and flattens (or inverts) the curve. Measured directly:
 *       the same BFS at five warm-up calls gave per-size medians of 341/314/328/617 µs
 *       (non-monotone, fitted exponent 0.26), and at 400 warm-up calls gave 28/63/124/254
 *       µs - a clean factor-2-per-doubling curve, fitted exponent 1.06.
 * </ul>
 *
 * <p>Hence {@link #warmupBudget}: warm-up is bounded by <em>time</em>, not by a call
 * count, so a cheap submission gets the hundreds of calls it needs to reach steady state
 * while an expensive one - which racks up back-edges quickly and would blow the whole
 * per-size timeout on warm-up alone - takes only the handful it needs.
 *
 * <h2>The knobs</h2>
 *
 * <ul>
 *   <li>{@link #sizes} (default {@code [4000, 8000, 16000, 32000]}) - the input sizes
 *       tried first, ascending, and by their count how many usable points the measurer
 *       aims for before it stops. Small enough that a genuinely quadratic submission, the
 *       case this check exists to catch, still finishes every size comfortably inside the
 *       shared {@code sweprep.grader.timeout}; four of them so the classifier has enough
 *       points to estimate its own fit uncertainty (it refuses to classify from fewer than
 *       three). This is why growing-input measurement never needed a looser timeout of its
 *       own - the sizes respect the existing one. A cheap submission whose smallest sizes
 *       time below the classifier's reliability floor is measured at further doublings
 *       instead; see {@link #maxSizes}.
 *   <li>{@link #warmupBudget} (default 150 ms) - how long untimed warm-up calls are made
 *       at each size before any timed one. 150 ms buys ~400 calls on the cheap linear
 *       submissions that used to misclassify (measured: monotonic stack ~170 µs/call at
 *       size 32 000, BFS ~250 µs/call), which is where their curves became clean, while
 *       costing an expensive submission at most one extra call.
 *   <li>{@link #maxWarmupCalls} (default 50 000) - a runaway guard, not a tuning knob:
 *       it bounds the loop for a submission so cheap that a call costs nothing, and is
 *       set high enough that the time budget is what actually ends warm-up in every
 *       measured case. It was originally 500, and that was itself a bug: at 500 the cap
 *       bound the <em>small</em> sizes while the budget bound the large ones, which
 *       re-introduced the same size-dependent warm-up bias in the opposite direction and
 *       left the real daily-temperatures reference solution unclassifiable.
 *   <li>{@link #repetitions} (default 9) - timed calls per size, of which the measurer
 *       takes the <em>median</em>. Median, not mean or min: after a real warm-up the
 *       remaining noise is one-sided (a GC pause, another process taking the CPU), so a
 *       median resists it without chasing the vectorisation-driven lows a min would.
 *       Post-warm-up the spread across nine repetitions measured 1.1-1.8x, so nine is
 *       comfortably enough to place a stable median.
 *   <li>{@link #maxSizes} (default 6) - the ceiling on how far the size ladder may be
 *       extended past {@link #sizes} when a submission is too cheap to measure at the
 *       configured sizes. Extension doubles the largest size each step, so 6 caps the
 *       largest measured input at 128 000 for the default ladder - big enough to lift a
 *       real linear reference solution clear of the reliability floor, small enough that
 *       generating one input stays cheap. Never reached by a submission whose configured
 *       sizes already measure reliably.
 *   <li>{@link #totalBudget} (default 30 s) - the ceiling on cumulative measurement
 *       wall-clock time. Measurement runs inside an interactive request (the complexity
 *       claim endpoint), so it has to be bounded: once this much time has been spent the
 *       measurer stops starting further sizes and classifies whatever it has, or reports
 *       inconclusive. Worst-case added latency is therefore {@code totalBudget} plus one
 *       size's {@code sweprep.grader.timeout} (the size already in flight when the budget
 *       runs out), i.e. ~40 s by default; typical measured cost is 1-6 s.
 * </ul>
 *
 * @param sizes          measured input sizes, ascending (sorted defensively if not)
 * @param warmupBudget   wall-clock time spent on untimed warm-up calls at each size,
 *                       before timing starts; at least one warm-up call is always made
 * @param maxWarmupCalls hard cap on warm-up calls per size, whatever the budget allows
 * @param repetitions    timed calls made at each size, after warm-up; the measurer
 *                       takes their median
 * @param maxSizes       ceiling on how many sizes are measured in total, including any
 *                       doublings appended past {@code sizes} when the configured ones
 *                       run too fast to classify; never below {@code sizes.size()}
 * @param totalBudget    ceiling on cumulative measurement wall-clock time across all
 *                       sizes; no further size is started once it is exhausted
 */
@ConfigurationProperties(prefix = "sweprep.complexity")
public record ComplexityProperties(
        List<Integer> sizes,
        Duration warmupBudget,
        Integer maxWarmupCalls,
        Integer repetitions,
        Integer maxSizes,
        Duration totalBudget) {

    private static final List<Integer> DEFAULT_SIZES = List.of(4_000, 8_000, 16_000, 32_000);
    private static final Duration DEFAULT_WARMUP_BUDGET = Duration.ofMillis(500);
    private static final int DEFAULT_MAX_WARMUP_CALLS = 50_000;
    private static final int DEFAULT_REPETITIONS = 9;
    private static final int DEFAULT_MAX_SIZES = 7;
    private static final Duration DEFAULT_TOTAL_BUDGET = Duration.ofSeconds(30);

    public ComplexityProperties {
        sizes = (sizes == null || sizes.isEmpty()) ? DEFAULT_SIZES : sizes.stream().sorted().toList();
        warmupBudget = (warmupBudget == null || warmupBudget.isNegative())
                ? DEFAULT_WARMUP_BUDGET
                : warmupBudget;
        maxWarmupCalls = (maxWarmupCalls == null || maxWarmupCalls < 0)
                ? DEFAULT_MAX_WARMUP_CALLS
                : maxWarmupCalls;
        repetitions = (repetitions == null || repetitions < 1) ? DEFAULT_REPETITIONS : repetitions;
        maxSizes = (maxSizes == null || maxSizes < sizes.size()) ? Math.max(DEFAULT_MAX_SIZES, sizes.size()) : maxSizes;
        totalBudget = (totalBudget == null || totalBudget.isNegative() || totalBudget.isZero())
                ? DEFAULT_TOTAL_BUDGET
                : totalBudget;
    }
}
