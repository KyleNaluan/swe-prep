package com.sweprep.backend.grader;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.language.JavaLanguageAdapter;
import com.sweprep.backend.language.LanguageAdapterRegistry;
import com.sweprep.backend.language.PythonLanguageAdapter;
import com.sweprep.backend.runner.LocalJavaRunner;
import com.sweprep.backend.runner.LocalPythonRunner;
import com.sweprep.backend.runner.RunnerRegistry;
import com.sweprep.backend.testsupport.Fixtures;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The tracer bullet's core proof, exercised without the web layer: a Java
 * submission is compiled and run against language-neutral cases, and every verdict
 * path is distinguished - all pass, some fail, a compile error, and a timeout.
 * The exercise is a synthetic demo ({@code pair(a, b)} returned in any order): no
 * real problem content lives in this repo (issue #14).
 *
 * <p>{@link #aSecondLanguageSolvesTheExactSameStoredExerciseWithNoContentChange()}
 * (issue #26) is the actual proof this repo asked for: the very same {@code pair}
 * {@link Fixtures#pairInAnyOrder()} fixture - untouched - graded through the Python
 * adapter and runner instead of Java's.
 */
class TestCaseGraderTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Exercise pair = Fixtures.pairInAnyOrder();

    private final LanguageAdapterRegistry adapters =
            new LanguageAdapterRegistry(List.of(new JavaLanguageAdapter(), new PythonLanguageAdapter()));
    private final RunnerRegistry runners =
            new RunnerRegistry(List.of(new LocalJavaRunner(), new LocalPythonRunner("python3")));

    private Grader grader(Duration timeout) {
        return new TestCaseGrader(adapters, runners, mapper, timeout);
    }

    @Test
    void supportsOnlyTestCaseGradedExercises() {
        assertThat(grader(Duration.ofSeconds(10)).supports(pair)).isTrue();
        assertThat(grader(Duration.ofSeconds(10)).supports(Fixtures.concept())).isFalse();
    }

    @Test
    void correctSubmissionPassesEveryCase() {
        Verdict verdict = grader(Duration.ofSeconds(10)).grade(pair, Fixtures.PAIR_SOLUTION);

        assertThat(verdict.outcome()).isEqualTo(Verdict.Outcome.PASSED);
        assertThat(verdict.passed()).isEqualTo(verdict.total());
        assertThat(verdict.total()).isEqualTo(3);
    }

    @Test
    void bothValidOrderingsAreAcceptedForAnOrderInsensitiveExercise() {
        // Returns the pair reversed. Under exact equality this would fail the case
        // whose representative ordering differs; the order-insensitive rule passes
        // them all.
        String reversed =
                """
                class Solution {
                    public int[] pair(int a, int b) {
                        return new int[] {b, a};
                    }
                }
                """;

        Verdict verdict = grader(Duration.ofSeconds(10)).grade(pair, reversed);

        assertThat(verdict.outcome()).isEqualTo(Verdict.Outcome.PASSED);
        assertThat(verdict.passed()).isEqualTo(verdict.total());
    }

    @Test
    void partiallyCorrectSubmissionCountsThePassingCases() {
        // Drops the second element, so only the [7, 7] case still matches.
        String dropsSecond =
                """
                class Solution {
                    public int[] pair(int a, int b) {
                        return new int[] {a, a};
                    }
                }
                """;

        Verdict verdict = grader(Duration.ofSeconds(10)).grade(pair, dropsSecond);

        assertThat(verdict.outcome()).isEqualTo(Verdict.Outcome.FAILED);
        assertThat(verdict.passed()).isEqualTo(1);
        assertThat(verdict.total()).isEqualTo(3);
    }

    @Test
    void aSolutionThatPrintsPastTheOutputCapIsStillGradedOnItsResults() {
        // Floods stdout past the runner's 1MB cap before returning the right answer.
        // The result lives in a dedicated file, off this channel, so it survives.
        String noisyButCorrect =
                """
                class Solution {
                    public int[] pair(int a, int b) {
                        for (int i = 0; i < 200_000; i++) {
                            System.out.println("noise line padding to exceed the output cap " + i);
                        }
                        return new int[] {a, b};
                    }
                }
                """;

        Verdict verdict = grader(Duration.ofSeconds(20)).grade(pair, noisyButCorrect);

        assertThat(verdict.outcome()).isEqualTo(Verdict.Outcome.PASSED);
        assertThat(verdict.passed()).isEqualTo(verdict.total());
    }

    @Test
    void compileErrorIsReportedAsSuchNotAsATestFailure() {
        String willNotCompile =
                """
                class Solution {
                    public int[] pair(int a, int b) {
                        return notAVariable;
                    }
                }
                """;

        Verdict verdict = grader(Duration.ofSeconds(10)).grade(pair, willNotCompile);

        assertThat(verdict.outcome()).isEqualTo(Verdict.Outcome.COMPILE_ERROR);
        assertThat(verdict.detail()).contains("Solution.java");
    }

    @Test
    void aVerdictCarriesTheRunTimeForDisplay() {
        Verdict verdict = grader(Duration.ofSeconds(10)).grade(pair, Fixtures.PAIR_SOLUTION);

        // Runtime is recorded for interest; a forked-JVM run takes real time, and it
        // never changes the pass/fail decision.
        assertThat(verdict.runtimeMillis()).isPositive();
        assertThat(verdict.outcome()).isEqualTo(Verdict.Outcome.PASSED);
    }

    @Test
    void firstFailingCaseDisclosesInputExpectedAndActualOnlyWhenAsked() {
        // Drops the second element: [1,2] and [5,3] fail, [7,7] passes. The reveal
        // hands over the first failing case with what the submission actually returned.
        String dropsSecond =
                """
                class Solution {
                    public int[] pair(int a, int b) {
                        return new int[] {a, a};
                    }
                }
                """;

        Optional<FailingCase> failing =
                grader(Duration.ofSeconds(10)).firstFailingCase(pair, dropsSecond);

        assertThat(failing).isPresent();
        assertThat(failing.get().input().toString()).isEqualTo("[1,2]");
        assertThat(failing.get().expected().toString()).isEqualTo("[1,2]");
        assertThat(failing.get().actual().toString()).isEqualTo("[1,1]");
        assertThat(failing.get().note()).isNull();
    }

    @Test
    void firstFailingCaseIsEmptyForAPassingSubmission() {
        assertThat(grader(Duration.ofSeconds(10)).firstFailingCase(pair, Fixtures.PAIR_SOLUTION))
                .isEmpty();
    }

    @Test
    void firstFailingCaseIsEmptyWhenTheCodeDoesNotCompile() {
        String willNotCompile =
                """
                class Solution {
                    public int[] pair(int a, int b) {
                        return notAVariable;
                    }
                }
                """;

        assertThat(grader(Duration.ofSeconds(10)).firstFailingCase(pair, willNotCompile)).isEmpty();
    }

    @Test
    void infiniteLoopIsCaughtByTheTimeout() {
        String infiniteLoop =
                """
                class Solution {
                    public int[] pair(int a, int b) {
                        while (true) {
                            // spin forever
                        }
                    }
                }
                """;

        Verdict verdict = grader(Duration.ofSeconds(2)).grade(pair, infiniteLoop);

        assertThat(verdict.outcome()).isEqualTo(Verdict.Outcome.TIMEOUT);
    }

    // --- A second language, proving the language-neutral test-case claim (issue #26) ----

    /** A correct Python solution to {@link Fixtures#pairInAnyOrder()}. */
    private static final String PAIR_SOLUTION_PYTHON =
            """
            class Solution:
                def pair(self, a: int, b: int) -> list[int]:
                    return [a, b]
            """;

    @Test
    void aSecondLanguageSolvesTheExactSameStoredExerciseWithNoContentChange() {
        // The `pair` Exercise (its Signature, its TestCases, its Comparison) is the
        // identical object graded by every Java test above - not a python-flavoured
        // copy. Only the requested language and the submission's own source differ.
        Verdict verdict = grader(Duration.ofSeconds(15)).grade(pair, PAIR_SOLUTION_PYTHON, "python");

        assertThat(verdict.outcome()).isEqualTo(Verdict.Outcome.PASSED);
        assertThat(verdict.passed()).isEqualTo(verdict.total());
        assertThat(verdict.total()).isEqualTo(3);
    }

    @Test
    void aPartiallyCorrectPythonSubmissionCountsThePassingCases() {
        String dropsSecond =
                """
                class Solution:
                    def pair(self, a: int, b: int) -> list[int]:
                        return [a, a]
                """;

        Verdict verdict = grader(Duration.ofSeconds(15)).grade(pair, dropsSecond, "python");

        assertThat(verdict.outcome()).isEqualTo(Verdict.Outcome.FAILED);
        assertThat(verdict.passed()).isEqualTo(1);
        assertThat(verdict.total()).isEqualTo(3);
    }

    @Test
    void aPythonSyntaxErrorIsReportedAsACompileErrorNotATestFailure() {
        String willNotParse =
                """
                class Solution:
                    def pair(self, a: int, b: int) -> list[int]:
                        return [a, b
                """;

        Verdict verdict = grader(Duration.ofSeconds(15)).grade(pair, willNotParse, "python");

        assertThat(verdict.outcome()).isEqualTo(Verdict.Outcome.COMPILE_ERROR);
    }

    @Test
    void firstFailingCaseWorksTheSameWayForAPythonSubmission() {
        String dropsSecond =
                """
                class Solution:
                    def pair(self, a: int, b: int) -> list[int]:
                        return [a, a]
                """;

        Optional<FailingCase> failing =
                grader(Duration.ofSeconds(15)).firstFailingCase(pair, dropsSecond, "python");

        assertThat(failing).isPresent();
        assertThat(failing.get().input().toString()).isEqualTo("[1,2]");
        assertThat(failing.get().expected().toString()).isEqualTo("[1,2]");
        assertThat(failing.get().actual().toString()).isEqualTo("[1,1]");
    }

    @Test
    void anUnknownLanguageIsRejected() {
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                        com.sweprep.backend.language.LanguageAdapterRegistry.UnsupportedLanguageException.class,
                        () -> grader(Duration.ofSeconds(5)).grade(pair, PAIR_SOLUTION_PYTHON, "cobol"))
                .getMessage())
                .contains("cobol");
    }

    @Test
    void aMissingLanguageStillDefaultsToJava() {
        // The 2-arg grade() overload, exactly as every non-language-aware caller uses it.
        Verdict verdict = grader(Duration.ofSeconds(10)).grade(pair, Fixtures.PAIR_SOLUTION);

        assertThat(verdict.outcome()).isEqualTo(Verdict.Outcome.PASSED);
    }
}
