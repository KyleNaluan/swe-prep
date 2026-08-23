package com.sweprep.backend.language;

import static org.assertj.core.api.Assertions.assertThat;

import com.sweprep.backend.exercise.DataType;
import com.sweprep.backend.exercise.Signature;
import com.sweprep.backend.exercise.Signature.Parameter;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The stub and harness are generated from the language-neutral signature, not
 * hand-written per problem - the same claim {@link JavaLanguageAdapterTest} proves
 * for Java, proven here for the second adapter (issue #26).
 */
class PythonLanguageAdapterTest {

    private final PythonLanguageAdapter adapter = new PythonLanguageAdapter();

    @Test
    void languageIdIsPython() {
        assertThat(adapter.languageId()).isEqualTo("python");
    }

    @Test
    void stubIsDerivedFromTheSignature() {
        Signature signature = new Signature(
                "twoSum",
                List.of(new Parameter("nums", DataType.INT_ARRAY), new Parameter("target", DataType.INT)),
                DataType.INT_ARRAY);

        String stub = adapter.generateStub(signature);

        assertThat(stub).contains("def twoSum(self, nums: list[int], target: int) -> list[int]:");
        assertThat(stub).contains("class Solution");
        assertThat(stub).contains("raise NotImplementedError");
    }

    @Test
    void aDifferentSignatureYieldsDifferentGeneratedPython() {
        Signature booleanReturning = new Signature(
                "isAnagram",
                List.of(new Parameter("s", DataType.STRING), new Parameter("t", DataType.STRING)),
                DataType.BOOLEAN);

        String stub = adapter.generateStub(booleanReturning);
        String harness =
                adapter.generateHarness(booleanReturning).sourceFiles().values().iterator().next();

        assertThat(stub).contains("def isAnagram(self, s: str, t: str) -> bool:");
        // Unlike Java, Python needs no per-type conversion code - every argument is a
        // plain positional extraction from the already-parsed JSON list.
        assertThat(harness).contains("arg0 = case_input[0]");
        assertThat(harness).contains("arg1 = case_input[1]");
        assertThat(harness).contains("solution.isAnagram(arg0, arg1)");
    }

    @Test
    void theHarnessImportsTheSubmissionModuleByName() {
        Signature signature = new Signature(
                "identity", List.of(new Parameter("n", DataType.INT)), DataType.INT);

        String harness = adapter.generateHarness(signature).sourceFiles().values().iterator().next();

        assertThat(harness).contains("from Solution import Solution");
    }

    @Test
    void generatesNoRuntimeClasspathSinceOnlyTheStandardLibraryIsUsed() {
        Signature signature = new Signature(
                "identity", List.of(new Parameter("n", DataType.INT)), DataType.INT);

        assertThat(adapter.generateHarness(signature).runtimeClasspath()).isEmpty();
        assertThat(adapter.generateTimingHarness(signature).runtimeClasspath()).isEmpty();
    }

    // --- The timing harness (issue #17) -------------------------------------------

    @Test
    void theTimingHarnessBindsArgumentsAndCallsTheSubmissionLikeTheCorrectnessHarness() {
        Signature signature = new Signature(
                "twoSum",
                List.of(new Parameter("nums", DataType.INT_ARRAY), new Parameter("target", DataType.INT)),
                DataType.INT_ARRAY);

        String harness =
                adapter.generateTimingHarness(signature).sourceFiles().values().iterator().next();

        // Each repetition works on a fresh deep copy of the input, so a solution that
        // mutates its argument in place is timed fairly on every repetition, not only the
        // first - the Python analogue of the Java harness re-binding via convertValue.
        assertThat(harness).contains("import copy");
        assertThat(harness).contains("arg0 = copy.deepcopy(case_input[0])");
        assertThat(harness).contains("solution.twoSum(arg0, arg1)");
        // Timing mode: repetitions and elapsed time, never a comparison against an
        // expected value - there is no Comparison in play here.
        assertThat(harness).contains("repetitions");
        assertThat(harness).contains("time.perf_counter_ns()");
        assertThat(harness).contains("elapsedNanos");
        assertThat(harness).doesNotContain("expected");
    }

    @Test
    void theTimingHarnessIsAFreshFileDistinctFromTheCorrectnessHarness() {
        Signature signature = new Signature(
                "identity", List.of(new Parameter("n", DataType.INT)), DataType.INT);

        var correctness = adapter.generateHarness(signature);
        var timing = adapter.generateTimingHarness(signature);

        assertThat(timing.mainClass()).isNotEqualTo(correctness.mainClass());
        assertThat(timing.sourceFiles().keySet())
                .doesNotContainAnyElementsOf(correctness.sourceFiles().keySet());
    }

    @Test
    void submissionFileNameIsAPythonModule() {
        assertThat(adapter.submissionFileName()).isEqualTo("Solution.py");
    }

    // --- Linked structures (issue #6's adopted LeetCode serialisation) -------------

    @Test
    void alinkedStructureIsBuiltAndSerialisedByTheSharedStructuresModule() {
        Signature signature = new Signature(
                "dropFirst", List.of(new Parameter("head", DataType.LIST_NODE)), DataType.LIST_NODE);

        var harness = adapter.generateHarness(signature);
        String source = harness.sourceFiles().get("Harness.py");

        assertThat(source).contains("import Structures");
        assertThat(source).contains("arg0 = Structures.build_list(case_input[0])");
        assertThat(source).contains("entry[\"returned\"] = Structures.serialize_list(actual)");
        assertThat(harness.sourceFiles()).containsKey("Structures.py");
    }

    @Test
    void atreeSignatureUsesTheLevelOrderHelpers() {
        Signature signature = new Signature(
                "dropRight", List.of(new Parameter("root", DataType.TREE_NODE)), DataType.TREE_NODE);

        String source = adapter.generateHarness(signature).sourceFiles().get("Harness.py");

        assertThat(source).contains("arg0 = Structures.build_tree(case_input[0])");
        assertThat(source).contains("Structures.serialize_tree(actual)");
    }

    @Test
    void thestubImportsTheNodeTypesTheHarnessSupplies() {
        Signature signature = new Signature(
                "dropFirst", List.of(new Parameter("head", DataType.LIST_NODE)), DataType.LIST_NODE);

        String stub = adapter.generateStub(signature);

        // Python evaluates an annotation at definition time, so the import is load-bearing
        // rather than decorative - without it the solver's own file would not even import.
        assertThat(stub).startsWith("from Structures import ListNode");
        assertThat(stub).contains("def dropFirst(self, head: ListNode) -> ListNode:");
    }

    @Test
    void asignatureWithNoLinkedStructureCarriesNoSupportModule() {
        Signature signature = new Signature(
                "identity", List.of(new Parameter("n", DataType.INT)), DataType.INT);

        var harness = adapter.generateHarness(signature);

        assertThat(harness.sourceFiles()).containsOnlyKeys("Harness.py");
        assertThat(adapter.generateStub(signature)).doesNotContain("Structures");
    }
}
