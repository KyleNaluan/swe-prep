package com.sweprep.backend.grader;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
        sources.put(JavaLanguageAdapter.SUBMISSION_CLASS + ".java", submission);

        ExecutionRequest request = new ExecutionRequest(
                sources,
                Map.of(CASES_FILE, casesJson(exercise.testCases())),
                harness.mainClass(),
                List.of(CASES_FILE),
                harness.runtimeClasspath(),
                timeout);

        ExecutionResult result = runner.execute(request);
        return switch (result.outcome()) {
            case COMPILE_ERROR -> Verdict.compileError(result.compilerMessage());
            case TIMEOUT -> Verdict.timeout(
                    total,
                    "Execution timed out after " + timeout.toSeconds() + "s (possible infinite loop)");
            case COMPLETED -> interpret(result, total);
        };
    }

    private Verdict interpret(ExecutionResult result, int total) {
        Integer passed = parsePassed(result.stdout());
        if (passed == null) {
            String detail = result.stderr().isBlank()
                    ? "The program did not report a result (exit code " + result.exitCode() + ")"
                    : result.stderr().strip();
            return Verdict.error(detail);
        }
        return Verdict.of(passed, total);
    }

    private static Integer parsePassed(String stdout) {
        Integer passed = null;
        for (String line : stdout.split("\\R")) {
            if (line.startsWith(JavaLanguageAdapter.SUMMARY_PREFIX)) {
                String[] parts = line.substring(JavaLanguageAdapter.SUMMARY_PREFIX.length()).trim().split("\\s+");
                if (parts.length >= 1) {
                    try {
                        passed = Integer.parseInt(parts[0]);
                    } catch (NumberFormatException ignored) {
                        // Ignore a malformed summary line; treated as no result.
                    }
                }
            }
        }
        return passed;
    }

    private String casesJson(List<TestCase> testCases) {
        ArrayNode cases = mapper.createArrayNode();
        for (TestCase testCase : testCases) {
            var node = mapper.createObjectNode();
            node.set("input", testCase.input());
            node.set("expected", testCase.expected());
            cases.add(node);
        }
        try {
            return mapper.writeValueAsString(cases);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialise test cases", e);
        }
    }
}
