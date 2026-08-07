package com.sweprep.backend.grader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.sweprep.backend.exercise.Comparison;
import com.sweprep.backend.exercise.Exercise;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Grades a submission against an exercise's language-neutral test cases by
 * generating a harness (via a {@link LanguageAdapter}), running it (via a
 * {@link Runner}), and interpreting the outcome into a {@link Verdict}. The
 * generic pass-counting logic lives here; nothing about it is Java-specific,
 * which is the whole point of the adapter seam.
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
    public Verdict grade(Exercise exercise, String submission) {
        int total = exercise.testCases().size();
        GeneratedHarness harness = adapter.generateHarness(exercise.signature());

        Map<String, String> sources = new HashMap<>(harness.sourceFiles());
        sources.put(JavaLanguageAdapter.SUBMISSION_CLASS + ".java", submission == null ? "" : submission);

        ExecutionRequest request = new ExecutionRequest(
                sources,
                Map.of(CASES_FILE, casesJson(exercise.testCases())),
                harness.mainClass(),
                List.of(CASES_FILE, RESULT_FILE),
                harness.runtimeClasspath(),
                List.of(RESULT_FILE),
                timeout);

        ExecutionResult result = runner.execute(request);
        return switch (result.outcome()) {
            case COMPILE_ERROR -> Verdict.compileError(result.compilerMessage());
            case TIMEOUT -> Verdict.timeout(
                    total,
                    "Execution timed out after " + timeout.toSeconds() + "s (possible infinite loop)");
            case COMPLETED -> interpret(exercise, result, total);
        };
    }

    /**
     * Counts how many cases pass by comparing each recorded return value against
     * the exercise's expected value under its declared {@link Comparison} rule.
     * The results come from the harness's dedicated result file, never from the
     * submission's stdout, so a solution that prints a lot and then returns
     * correctly is still graded on its answers.
     */
    private Verdict interpret(Exercise exercise, ExecutionResult result, int total) {
        if (result.oversizedOutputFiles().contains(RESULT_FILE)) {
            return Verdict.error("The program's result was too large to read back");
        }
        String resultJson = result.outputFiles().get(RESULT_FILE);
        if (resultJson == null || resultJson.isBlank()) {
            return noResult(result);
        }
        JsonNode results;
        try {
            results = mapper.readTree(resultJson);
        } catch (Exception e) {
            return noResult(result);
        }
        if (!results.isArray() || results.size() != total) {
            return noResult(result);
        }

        Comparison comparison = exercise.comparison();
        List<TestCase> cases = exercise.testCases();
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
