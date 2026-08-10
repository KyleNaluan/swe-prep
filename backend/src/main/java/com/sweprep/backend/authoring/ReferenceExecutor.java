package com.sweprep.backend.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.sweprep.backend.exercise.Comparison;
import com.sweprep.backend.exercise.Signature;
import com.sweprep.backend.exercise.TestCase;
import com.sweprep.backend.language.GeneratedHarness;
import com.sweprep.backend.language.JavaLanguageAdapter;
import com.sweprep.backend.language.LanguageAdapter;
import com.sweprep.backend.runner.ExecutionRequest;
import com.sweprep.backend.runner.ExecutionResult;
import com.sweprep.backend.runner.LocalJavaRunner;
import com.sweprep.backend.runner.Runner;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Compiles and runs a candidate {@code Solution} source against language-neutral
 * test cases, using exactly the same {@link LanguageAdapter}/{@link Runner} pair
 * (and the same generated harness) the app's own {@code TestCaseGrader} grades a
 * submission with - so "the reference solution passes its own cases" and "this
 * mutation genuinely fails a case" mean the same thing here as they would to a
 * learner's submission, not an approximation of it.
 *
 * <p>Both classes it wraps are plain, dependency-free {@code @Component}s (no
 * injected collaborators), so this tool instantiates them directly rather than
 * standing up a Spring context - the content-entry flow has no need of one, and
 * skipping it keeps the tool fast to start and independent of the app's
 * datasource.
 */
final class ReferenceExecutor {

    private static final String CASES_FILE = "cases.json";
    private static final String RESULT_FILE = "results.json";

    private final LanguageAdapter adapter = new JavaLanguageAdapter();
    private final Runner runner = new LocalJavaRunner();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Duration timeout;

    ReferenceExecutor() {
        this(Duration.ofSeconds(10));
    }

    ReferenceExecutor(Duration timeout) {
        this.timeout = timeout;
    }

    /**
     * Runs {@code source} against every one of {@code cases}, in order. Never throws
     * on a submission-level failure (a compile error, a timeout, a thrown case) - all
     * three are ordinary {@link RunResult} values, exactly like a learner's own
     * submission would produce, so a caller decides what a given outcome means (a
     * reference solution that fails to compile is an authoring error; a mutation that
     * fails to compile is simply a mutation to discard and try another).
     */
    RunResult run(Signature signature, String source, List<TestCase> cases) {
        GeneratedHarness harness = adapter.generateHarness(signature);
        Map<String, String> sources = new HashMap<>(harness.sourceFiles());
        sources.put(JavaLanguageAdapter.SUBMISSION_CLASS + ".java", source);

        ExecutionRequest request = new ExecutionRequest(
                sources,
                Map.of(CASES_FILE, casesJson(cases)),
                harness.mainClass(),
                List.of(CASES_FILE, RESULT_FILE),
                harness.runtimeClasspath(),
                List.of(RESULT_FILE),
                timeout);
        ExecutionResult result = runner.execute(request);
        return switch (result.outcome()) {
            case COMPILE_ERROR -> new RunResult.CompileError(result.compilerMessage());
            case TIMEOUT -> new RunResult.TimedOut();
            case COMPLETED -> interpret(result, cases);
        };
    }

    /**
     * Runs {@code source} once, for the single {@code input} - the predict-output
     * derivation's "run the reference on a small input" (issue #24). {@code
     * expected} is never inspected (there is nothing to grade; the caller wants the
     * actual returned value), so it is a placeholder.
     */
    RunResult.CaseOutcome callOnce(Signature signature, String source, JsonNode input) {
        RunResult result = run(signature, source, List.of(new TestCase(input, NullNode.instance)));
        if (!(result instanceof RunResult.Completed completed)) {
            throw new AuthoringException(
                    "reference solution did not complete for predict-output input " + input + ": " + result);
        }
        return completed.cases().get(0);
    }

    private RunResult.Completed interpret(ExecutionResult result, List<TestCase> cases) {
        JsonNode results = resultArray(result, cases.size());
        List<RunResult.CaseOutcome> outcomes = new ArrayList<>();
        for (int i = 0; i < cases.size(); i++) {
            JsonNode entry = results == null ? null : results.get(i);
            if (entry == null) {
                outcomes.add(new RunResult.CaseOutcome(cases.get(i), null, false));
            } else if (entry.path("threw").asBoolean(false)) {
                outcomes.add(new RunResult.CaseOutcome(cases.get(i), null, true));
            } else {
                outcomes.add(new RunResult.CaseOutcome(cases.get(i), entry.get("returned"), false));
            }
        }
        return new RunResult.Completed(outcomes);
    }

    private JsonNode resultArray(ExecutionResult result, int total) {
        String resultJson = result.outputFiles().get(RESULT_FILE);
        if (resultJson == null || resultJson.isBlank()) {
            return null;
        }
        try {
            JsonNode results = mapper.readTree(resultJson);
            return results.isArray() && results.size() == total ? results : null;
        } catch (Exception e) {
            return null;
        }
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

    /** The outcome of one {@link #run} call. */
    sealed interface RunResult permits RunResult.CompileError, RunResult.TimedOut, RunResult.Completed {

        record CompileError(String message) implements RunResult {}

        record TimedOut() implements RunResult {}

        /** @param cases one outcome per input case, in the same order they were given */
        record Completed(List<CaseOutcome> cases) implements RunResult {

            /** Whether every case both produced a value and matched its expected value. */
            boolean allPass(Comparison comparison) {
                return cases.stream().allMatch(c -> c.matches(comparison));
            }

            /** Whether at least one case failed to match its expected value (threw or wrong). */
            boolean anyFail(Comparison comparison) {
                return cases.stream().anyMatch(c -> !c.matches(comparison));
            }
        }

        /**
         * @param testCase the case this outcome is for
         * @param returned the value the submission returned, or {@code null} if it threw
         *                 or produced no readable result
         * @param threw    whether the call threw
         */
        record CaseOutcome(TestCase testCase, JsonNode returned, boolean threw) {

            boolean matches(Comparison comparison) {
                return !threw && returned != null && comparison.matches(testCase.expected(), returned);
            }
        }
    }
}
