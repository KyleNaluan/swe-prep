package com.sweprep.backend.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sweprep.backend.authoring.ReferenceExecutor.RunResult;
import com.sweprep.backend.exercise.Comparison;
import com.sweprep.backend.exercise.DataType;
import com.sweprep.backend.exercise.Signature;
import com.sweprep.backend.exercise.Signature.Parameter;
import com.sweprep.backend.exercise.TestCase;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Proves {@link ReferenceExecutor} genuinely compiles and runs Java through the
 * same {@code LocalJavaRunner}/{@code JavaLanguageAdapter} pair the app grades a
 * learner's submission with - the empirical backbone {@code RepDeriver} builds
 * every "this mutation is actually a bug" and "this is actually what the
 * reference returns" claim on.
 */
class ReferenceExecutorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ReferenceExecutor executor = new ReferenceExecutor();

    private static final Signature DOUBLE_SIGNATURE =
            new Signature("doubleIt", List.of(new Parameter("n", DataType.INT)), DataType.INT);

    @Test
    void aCorrectSolutionPassesEveryCase() throws Exception {
        String source = "class Solution { public int doubleIt(int n) { return n * 2; } }";
        List<TestCase> cases = List.of(testCase("[3]", "6"), testCase("[0]", "0"));

        RunResult result = executor.run(DOUBLE_SIGNATURE, source, cases);

        assertThat(result).isInstanceOf(RunResult.Completed.class);
        assertThat(((RunResult.Completed) result).allPass(Comparison.exact())).isTrue();
    }

    @Test
    void aWrongSolutionFailsAtLeastOneCase() throws Exception {
        String source = "class Solution { public int doubleIt(int n) { return n; } }";
        List<TestCase> cases = List.of(testCase("[3]", "6"));

        RunResult result = executor.run(DOUBLE_SIGNATURE, source, cases);

        assertThat(result).isInstanceOf(RunResult.Completed.class);
        assertThat(((RunResult.Completed) result).anyFail(Comparison.exact())).isTrue();
    }

    @Test
    void sourceThatDoesNotCompileIsReportedAsACompileError() throws Exception {
        String source = "class Solution { this is not java }";

        RunResult result = executor.run(DOUBLE_SIGNATURE, source, List.of(testCase("[3]", "6")));

        assertThat(result).isInstanceOf(RunResult.CompileError.class);
        assertThat(((RunResult.CompileError) result).message()).isNotBlank();
    }

    @Test
    void aThrowingCallIsReportedAsThrownNotSilentlyWrong() throws Exception {
        String source = "class Solution { public int doubleIt(int n) { throw new RuntimeException(); } }";

        RunResult result = executor.run(DOUBLE_SIGNATURE, source, List.of(testCase("[3]", "6")));

        assertThat(result).isInstanceOf(RunResult.Completed.class);
        RunResult.Completed completed = (RunResult.Completed) result;
        assertThat(completed.cases().get(0).threw()).isTrue();
        assertThat(completed.anyFail(Comparison.exact())).isTrue();
    }

    @Test
    void callOnceReturnsTheActualValueTheSolutionReturns() throws Exception {
        String source = "class Solution { public int doubleIt(int n) { return n * 2; } }";
        JsonNode input = mapper.readTree("[21]");

        RunResult.CaseOutcome outcome = executor.callOnce(DOUBLE_SIGNATURE, source, input);

        assertThat(outcome.threw()).isFalse();
        assertThat(outcome.returned().asInt()).isEqualTo(42);
    }

    private TestCase testCase(String input, String expected) throws Exception {
        return new TestCase(mapper.readTree(input), mapper.readTree(expected));
    }
}
