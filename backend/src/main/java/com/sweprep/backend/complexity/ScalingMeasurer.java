package com.sweprep.backend.complexity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sweprep.backend.exercise.ComplexityCheck;
import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.InputGenerator;
import com.sweprep.backend.exercise.Response;
import com.sweprep.backend.exercise.Signature;
import com.sweprep.backend.language.GeneratedHarness;
import com.sweprep.backend.language.LanguageAdapter;
import com.sweprep.backend.language.LanguageAdapterRegistry;
import com.sweprep.backend.runner.ExecutionRequest;
import com.sweprep.backend.runner.ExecutionResult;
import com.sweprep.backend.runner.Runner;
import com.sweprep.backend.runner.RunnerRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Empirically checks a passing submission's self-reported time complexity by running
 * it at several growing input sizes and fitting the timing curve (issue #17).
 *
 * <p>This is the runner's "second execution mode" the ticket asks for: rather than a
 * new method on {@link Runner}, it is a different generated harness (timing, not
 * correctness) run through the exact same {@link Runner#execute} contract, one size
 * per call. That is a deliberate choice, not an oversight - {@link Runner} stays a
 * one-method seam, and growing-input measurement inherits precisely the isolation and
 * per-call timeout every other execution gets, never a second, looser route (see the
 * project notes on the runner's execution guarantees). Sizes are measured in ascending
 * order and the loop stops at the first {@link ExecutionResult.Outcome#TIMEOUT}, so a
 * badly slow submission cannot blow the whole measurement budget chasing sizes it was
 * never going to finish - whatever smaller sizes it did complete are still fit, or the
 * result is {@link MeasurementOutcome.Inconclusive} if too few remain. {@link
 * ComplexityProperties}'s default sizes are chosen so even a genuinely quadratic
 * submission - the case this check exists to catch - comfortably finishes every size
 * inside the shared {@code sweprep.grader.timeout}.
 *
 * <p>Every failure mode this class can hit collapses to {@link
 * MeasurementOutcome.Inconclusive} rather than a guess: a submission that throws on
 * generated inputs, one whose result is unreadable, one that never produces enough
 * usable sizes. Never {@link MeasurementOutcome.Conclusive} without genuine signal.
 *
 * <p>Which language the submission is measured as is resolved per call through {@link
 * LanguageAdapterRegistry}/{@link RunnerRegistry} (issue #26), the same seam {@code
 * TestCaseGrader} uses - a submission solved in a second language gets its scaling
 * measured with that language's own timing harness, not assumed to be Java.
 */
@Component
public class ScalingMeasurer {

    private static final String INPUT_FILE = "input.json";
    private static final String RESULT_FILE = "timing.json";

    private final LanguageAdapterRegistry adapters;
    private final RunnerRegistry runners;
    private final ObjectMapper mapper;
    private final ComplexityProperties properties;
    private final Duration timeout;

    public ScalingMeasurer(
            LanguageAdapterRegistry adapters,
            RunnerRegistry runners,
            ObjectMapper mapper,
            ComplexityProperties properties,
            @Value("${sweprep.grader.timeout:PT10S}") Duration timeout) {
        this.adapters = adapters;
        this.runners = runners;
        this.mapper = mapper;
        this.properties = properties;
        this.timeout = timeout;
    }

    /**
     * Measures {@code submission} (written in {@code language})'s time-scaling against
     * {@code exercise}, or {@link MeasurementOutcome.Skipped} when the exercise carries
     * no {@link InputGenerator} - optional content metadata, never an error (issue
     * #17's explicit acceptance criterion: "an exercise with no input generator skips
     * the check entirely without error").
     *
     * @throws IllegalStateException if the exercise declares a generator but has no code
     *                                response to run it against - an authoring error, not
     *                                the optional-metadata case above
     */
    public MeasurementOutcome measure(Exercise exercise, String submission, String language) {
        ComplexityCheck check = exercise.complexityCheck();
        if (check == null || check.generator() == null) {
            return new MeasurementOutcome.Skipped();
        }
        if (!(exercise.response() instanceof Response.Code code)) {
            throw new IllegalStateException(
                    "Exercise '" + exercise.id() + "' declares a complexity input generator but"
                            + " has no code response to run it against");
        }

        Signature signature = code.signature();
        InputGenerator generator = check.generator();
        LanguageAdapter adapter = adapters.forLanguage(language);
        Runner runner = runners.forLanguage(language);
        GeneratedHarness harness = adapter.generateTimingHarness(signature);

        List<ComplexityClassifier.SizeTiming> points = new ArrayList<>();
        for (int size : properties.sizes()) {
            JsonNode input = generator.generate(size, seedFor(exercise.id(), size));
            SizeSample sample = runOneSize(adapter, runner, harness, submission, input);
            if (sample instanceof SizeSample.TimedOut) {
                // Ascending sizes: nothing larger is worth attempting either.
                break;
            }
            if (sample instanceof SizeSample.Measured measured) {
                points.add(new ComplexityClassifier.SizeTiming(size, measured.medianNanos()));
            }
            // SizeSample.Unusable: this size produced no usable sample (every repetition
            // threw, or the result was unreadable) - skip it and keep trying larger ones.
        }

        if (points.size() < 2) {
            return new MeasurementOutcome.Inconclusive(
                    "the submission could not be measured at enough input sizes to fit a trend "
                            + "(it may not run to completion on the generated inputs, or ran out "
                            + "of measurement time)");
        }
        return ComplexityClassifier.classify(points);
    }

    /** What running the timing harness at one size produced. */
    private sealed interface SizeSample permits SizeSample.Measured, SizeSample.TimedOut, SizeSample.Unusable {
        record Measured(double medianNanos) implements SizeSample {}

        record TimedOut() implements SizeSample {}

        record Unusable() implements SizeSample {}
    }

    private SizeSample runOneSize(
            LanguageAdapter adapter, Runner runner, GeneratedHarness harness, String submission, JsonNode input) {
        Map<String, String> sources = new HashMap<>(harness.sourceFiles());
        sources.put(adapter.submissionFileName(), submission == null ? "" : submission);

        ExecutionRequest request = new ExecutionRequest(
                sources,
                Map.of(INPUT_FILE, input.toString()),
                harness.mainClass(),
                List.of(
                        INPUT_FILE,
                        String.valueOf(properties.warmupRepetitions()),
                        String.valueOf(properties.repetitions()),
                        RESULT_FILE),
                harness.runtimeClasspath(),
                List.of(RESULT_FILE),
                timeout);

        ExecutionResult result = runner.execute(request);
        if (result.outcome() == ExecutionResult.Outcome.TIMEOUT) {
            return new SizeSample.TimedOut();
        }
        if (result.outcome() != ExecutionResult.Outcome.COMPLETED) {
            return new SizeSample.Unusable();
        }
        return medianOf(result);
    }

    /**
     * The median elapsed time across the repetitions that produced a usable sample, or
     * {@link SizeSample.Unusable} when none did (every repetition threw on this
     * generated input, or the result file was missing or unreadable). The median, not
     * the mean, is what keeps one contention-noise outlier (a GC pause, another process
     * getting the CPU) from shifting the size's representative time on its own; the
     * warm-up calls the harness makes before any timed repetition (see {@link
     * com.sweprep.backend.language.LanguageAdapter#generateTimingHarness}) are what keep
     * a JIT-warmup transition - a distinct effect, not contention noise - from doing the
     * same thing.
     */
    private SizeSample medianOf(ExecutionResult result) {
        String json = result.outputFiles().get(RESULT_FILE);
        if (json == null || json.isBlank()) {
            return new SizeSample.Unusable();
        }
        JsonNode entries;
        try {
            entries = mapper.readTree(json);
        } catch (Exception e) {
            return new SizeSample.Unusable();
        }
        if (!entries.isArray()) {
            return new SizeSample.Unusable();
        }
        List<Double> usable = new ArrayList<>();
        for (JsonNode entry : entries) {
            JsonNode elapsed = entry.get("elapsedNanos");
            if (elapsed != null) {
                usable.add(elapsed.asDouble());
            }
        }
        if (usable.isEmpty()) {
            return new SizeSample.Unusable();
        }
        usable.sort(Double::compareTo);
        int mid = usable.size() / 2;
        double median = usable.size() % 2 == 0
                ? (usable.get(mid - 1) + usable.get(mid)) / 2.0
                : usable.get(mid);
        return new SizeSample.Measured(median);
    }

    /** Deterministic per (exercise, size) seed, so repeated measurement is reproducible. */
    private static long seedFor(String exerciseId, int size) {
        return java.util.Objects.hash(exerciseId, size);
    }
}
