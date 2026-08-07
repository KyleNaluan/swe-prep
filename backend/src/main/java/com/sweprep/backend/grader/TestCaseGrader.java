package com.sweprep.backend.grader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.sweprep.backend.exercise.Comparison;
import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.Grading;
import com.sweprep.backend.exercise.Response;
import com.sweprep.backend.exercise.Signature;
import com.sweprep.backend.exercise.TestCase;
import com.sweprep.backend.language.GeneratedHarness;
import com.sweprep.backend.language.JavaLanguageAdapter;
import com.sweprep.backend.language.LanguageAdapter;
import com.sweprep.backend.runner.ExecutionRequest;
import com.sweprep.backend.runner.ExecutionResult;
import com.sweprep.backend.runner.Runner;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Grades a submission against an exercise's language-neutral test cases by
 * generating a harness (via a {@link LanguageAdapter}), running it (via a
 * {@link Runner}), and interpreting the outcome into a {@link Verdict}. It handles
 * exercises whose grading spec is {@link Grading.TestCases}; the generic
 * pass-counting logic lives here and nothing about it is Java-specific, which is
 * the whole point of the adapter seam.
 */
@Component
public class TestCaseGrader implements Grader {

    private static final String CASES_FILE = "cases.json";

    /**
     * Where the harness writes each case's raw return value. It is a runner output
     * file, not part of the submission's stdout, so the submission cannot truncate
     * it away by printing (see issue #31, finding 3).
     */
    private static final String RESULT_FILE = "results.json";

    private final LanguageAdapter adapter;
    private final Runner runner;
    private final ObjectMapper mapper;
    private final Duration timeout;

    public TestCaseGrader(
            LanguageAdapter adapter,
            Runner runner,
            ObjectMapper mapper,
            @Value("${sweprep.grader.timeout:PT10S}") Duration timeout) {
        this.adapter = adapter;
        this.runner = runner;
        this.mapper = mapper;
        this.timeout = timeout;
    }

    @Override
    public boolean supports(Exercise exercise) {
        return exercise.grading() instanceof Grading.TestCases;
    }

    @Override
    public Verdict grade(Exercise exercise, String submission) {
        Grading.TestCases spec = (Grading.TestCases) exercise.grading();
        int total = spec.cases().size();

        Run run = execute(exercise, submission);
        ExecutionResult result = run.result();
        // Runtime is attached to every verdict for interest only; it never changes the
        // pass/fail decision (issue #16/#5).
        return (switch (result.outcome()) {
            case COMPILE_ERROR -> Verdict.compileError(result.compilerMessage());
            case TIMEOUT -> Verdict.timeout(
                    total,
                    "Execution timed out after " + timeout.toSeconds() + "s (possible infinite loop)");
            case COMPLETED -> interpret(spec, result, total);
        }).withRuntime(run.millis());
    }

    /**
     * Discloses the first case the submission fails, only when the solver has
     * explicitly asked to see it (issues #16/#5). It re-runs the submission and walks
     * the cases in order, returning the first that threw or whose returned value does
     * not match the expected one under the comparison rule. Empty means there was
     * nothing to reveal: the submission passed every case, or it never produced a
     * per-case result (it did not compile, timed out, or its result was unreadable).
     */
    @Override
    public Optional<FailingCase> firstFailingCase(Exercise exercise, String submission) {
        Grading.TestCases spec = (Grading.TestCases) exercise.grading();
        int total = spec.cases().size();

        ExecutionResult result = execute(exercise, submission).result();
        if (result.outcome() != ExecutionResult.Outcome.COMPLETED) {
            return Optional.empty();
        }
        JsonNode results = resultArray(result, total);
        if (results == null) {
            return Optional.empty();
        }

        Comparison comparison = spec.comparison();
        List<TestCase> cases = spec.cases();
        for (int i = 0; i < total; i++) {
            JsonNode entry = results.get(i);
            TestCase testCase = cases.get(i);
            if (entry != null && entry.path("threw").asBoolean(false)) {
                return Optional.of(new FailingCase(
                        testCase.input(), testCase.expected(), null,
                        "the submission threw on this case"));
            }
            JsonNode actual = entry == null ? null : entry.get("returned");
            if (actual == null || !comparison.matches(testCase.expected(), actual)) {
                return Optional.of(new FailingCase(
                        testCase.input(), testCase.expected(), actual,
                        actual == null ? "the submission returned no value for this case" : null));
            }
        }
        return Optional.empty();
    }

    /** Compiles and runs the submission against the exercise's cases, timing the run. */
    private Run execute(Exercise exercise, String submission) {
        Grading.TestCases spec = (Grading.TestCases) exercise.grading();
        Signature signature = codeSignature(exercise);
        GeneratedHarness harness = adapter.generateHarness(signature);

        Map<String, String> sources = new HashMap<>(harness.sourceFiles());
        sources.put(JavaLanguageAdapter.SUBMISSION_CLASS + ".java", submission == null ? "" : submission);

        ExecutionRequest request = new ExecutionRequest(
                sources,
                Map.of(CASES_FILE, casesJson(spec.cases())),
                harness.mainClass(),
                List.of(CASES_FILE, RESULT_FILE),
                harness.runtimeClasspath(),
                List.of(RESULT_FILE),
                timeout);

        long startNanos = System.nanoTime();
        ExecutionResult result = runner.execute(request);
        long millis = (System.nanoTime() - startNanos) / 1_000_000L;
        return new Run(result, millis);
    }

    /** An execution paired with how long it took, in milliseconds (display only). */
    private record Run(ExecutionResult result, long millis) {}

    /** The signature the harness is generated from - test-case grading needs a code response. */
    private static Signature codeSignature(Exercise exercise) {
        if (exercise.response() instanceof Response.Code code) {
            return code.signature();
        }
        throw new IllegalStateException(
                "Exercise '" + exercise.id() + "' is graded by test cases but has no code response");
    }

    /**
     * Counts how many cases pass by comparing each recorded return value against
     * the expected value under the grading spec's declared {@link Comparison} rule.
     * The results come from the harness's dedicated result file, never from the
     * submission's stdout, so a solution that prints a lot and then returns
     * correctly is still graded on its answers.
     */
    private Verdict interpret(Grading.TestCases spec, ExecutionResult result, int total) {
        if (result.oversizedOutputFiles().contains(RESULT_FILE)) {
            return Verdict.error("The program's result was too large to read back");
        }
        JsonNode results = resultArray(result, total);
        if (results == null) {
            return noResult(result);
        }

        Comparison comparison = spec.comparison();
        List<TestCase> cases = spec.cases();
        int passed = 0;
        for (int i = 0; i < total; i++) {
            JsonNode entry = results.get(i);
            if (entry == null || entry.path("threw").asBoolean(false)) {
                continue;
            }
            JsonNode actual = entry.get("returned");
            if (actual != null && comparison.matches(cases.get(i).expected(), actual)) {
                passed++;
            }
        }
        return Verdict.of(passed, total);
    }

    /**
     * The harness's per-case results as a JSON array, or {@code null} when there is no
     * usable result to read: the file is absent or blank, is not valid JSON, or does
     * not hold one entry per case. Shared by the verdict count and the failing-case
     * reveal so both read the results the same way.
     */
    private JsonNode resultArray(ExecutionResult result, int total) {
        String resultJson = result.outputFiles().get(RESULT_FILE);
        if (resultJson == null || resultJson.isBlank()) {
            return null;
        }
        JsonNode results;
        try {
            results = mapper.readTree(resultJson);
        } catch (Exception e) {
            return null;
        }
        if (!results.isArray() || results.size() != total) {
            return null;
        }
        return results;
    }

    private static Verdict noResult(ExecutionResult result) {
        String detail = result.stderr().isBlank()
                ? "The program did not report a result (exit code " + result.exitCode() + ")"
                : result.stderr().strip();
        return Verdict.error(detail);
    }

    private String casesJson(List<TestCase> testCases) {
        ArrayNode cases = mapper.createArrayNode();
        for (TestCase testCase : testCases) {
            var node = mapper.createObjectNode();
            node.set("input", testCase.input());
            cases.add(node);
        }
        try {
            return mapper.writeValueAsString(cases);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialise test cases", e);
        }
    }
}
