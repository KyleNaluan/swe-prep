package com.sweprep.backend.language;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.sweprep.backend.exercise.Comparison;
import com.sweprep.backend.exercise.DataType;
import com.sweprep.backend.exercise.Difficulty;
import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.Form;
import com.sweprep.backend.exercise.Grading;
import com.sweprep.backend.exercise.Response;
import com.sweprep.backend.exercise.Signature;
import com.sweprep.backend.exercise.Signature.Parameter;
import com.sweprep.backend.exercise.TestCase;
import com.sweprep.backend.grader.Grader;
import com.sweprep.backend.grader.TestCaseGrader;
import com.sweprep.backend.grader.Verdict;
import com.sweprep.backend.runner.LocalJavaRunner;
import com.sweprep.backend.runner.LocalPythonRunner;
import com.sweprep.backend.runner.RunnerRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Round-trip properties of the LIST_NODE / TREE_NODE serialisation, checked against the
 * <em>real generated harness</em> in both languages rather than a Java-side copy of the
 * rules - a copy could agree with itself while disagreeing with what actually runs.
 *
 * <p>The device is an identity submission: the harness builds the structure from a
 * case's JSON, the submission hands it straight back, and the harness serialises it
 * again. Grading that against the case's own input is exactly the property
 * {@code serialise(build(x)) == x}, and running the same generated cases through Java
 * and Python is what proves a case authored once means the same thing in both.
 *
 * <p>Shapes are generated from a fixed seed, so a failure is reproducible, and cover the
 * places a convention is easiest to get wrong: the empty structure in both its spellings
 * ({@code []} and {@code null}), a single node, internal nulls, and the trailing nulls a
 * level-order array must trim.
 */
class LinkedStructureSerializationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long SEED = 20260823L;
    private static final int GENERATED_SHAPES = 40;

    private final Grader grader = new TestCaseGrader(
            new LanguageAdapterRegistry(List.of(new JavaLanguageAdapter(), new PythonLanguageAdapter())),
            new RunnerRegistry(List.of(new LocalJavaRunner(), new LocalPythonRunner("python3"))),
            MAPPER,
            Duration.ofSeconds(30));

    private static final String LIST_IDENTITY_JAVA =
            """
            class Solution {
                public ListNode identity(ListNode head) {
                    return head;
                }
            }
            """;

    private static final String LIST_IDENTITY_PYTHON =
            """
            from Structures import ListNode


            class Solution:
                def identity(self, head: ListNode) -> ListNode:
                    return head
            """;

    private static final String TREE_IDENTITY_JAVA =
            """
            class Solution {
                public TreeNode identity(TreeNode root) {
                    return root;
                }
            }
            """;

    private static final String TREE_IDENTITY_PYTHON =
            """
            from Structures import TreeNode


            class Solution:
                def identity(self, root: TreeNode) -> TreeNode:
                    return root
            """;

    // --- LIST_NODE ----------------------------------------------------------------

    @Test
    void everyGeneratedListSurvivesBuildAndSerialiseInJava() {
        Exercise roundTrip = identityExercise(DataType.LIST_NODE, listShapes());

        Verdict verdict = grader.grade(roundTrip, LIST_IDENTITY_JAVA, "java");

        assertThat(verdict.outcome()).isEqualTo(Verdict.Outcome.PASSED);
        assertThat(verdict.passed()).isEqualTo(verdict.total());
    }

    @Test
    void everyGeneratedListSurvivesBuildAndSerialiseInPython() {
        Exercise roundTrip = identityExercise(DataType.LIST_NODE, listShapes());

        Verdict verdict = grader.grade(roundTrip, LIST_IDENTITY_PYTHON, "python");

        assertThat(verdict.outcome()).isEqualTo(Verdict.Outcome.PASSED);
        assertThat(verdict.passed()).isEqualTo(verdict.total());
    }

    // --- TREE_NODE ----------------------------------------------------------------

    @Test
    void everyGeneratedTreeSurvivesBuildAndSerialiseInJava() {
        Exercise roundTrip = identityExercise(DataType.TREE_NODE, treeShapes());

        Verdict verdict = grader.grade(roundTrip, TREE_IDENTITY_JAVA, "java");

        assertThat(verdict.outcome()).isEqualTo(Verdict.Outcome.PASSED);
        assertThat(verdict.passed()).isEqualTo(verdict.total());
    }

    @Test
    void everyGeneratedTreeSurvivesBuildAndSerialiseInPython() {
        Exercise roundTrip = identityExercise(DataType.TREE_NODE, treeShapes());

        Verdict verdict = grader.grade(roundTrip, TREE_IDENTITY_PYTHON, "python");

        assertThat(verdict.outcome()).isEqualTo(Verdict.Outcome.PASSED);
        assertThat(verdict.passed()).isEqualTo(verdict.total());
    }

    // --- The cycle input form -----------------------------------------------------

    @Test
    void aCyclicListIsBuiltAndHandedToTheSolverInBothLanguages() {
        Exercise revisits = com.sweprep.backend.testsupport.Fixtures.listRevisitsItself();

        Verdict java = grader.grade(
                revisits, com.sweprep.backend.testsupport.Fixtures.LIST_REVISITS_SOLUTION, "java");
        Verdict python = grader.grade(
                revisits,
                com.sweprep.backend.testsupport.Fixtures.LIST_REVISITS_SOLUTION_PYTHON,
                "python");

        // Every case passes only if the tail really was joined back to index `pos` - a
        // list built without the cycle would answer false where the case says true.
        assertThat(java.outcome()).isEqualTo(Verdict.Outcome.PASSED);
        assertThat(python.outcome()).isEqualTo(Verdict.Outcome.PASSED);
        assertThat(java.total()).isEqualTo(python.total());
    }

    /**
     * The design constraint that a cyclic structure can never hang the serialiser: a
     * submission that hands one back fails its case (the harness reports it threw) and
     * the run <em>finishes</em>. A timeout here would mean the serialiser was walking the
     * cycle forever, which is precisely what the identity-tracking exists to prevent.
     */
    @Test
    void handingBackACyclicListFailsTheCaseRatherThanHangingTheHarness() {
        Exercise roundTrip = identityExercise(
                DataType.LIST_NODE,
                List.of(new TestCase(json("[{ \"values\": [1, 2, 3], \"pos\": 0 }]"), json("[1, 2, 3]"))));

        Verdict java = grader.grade(roundTrip, LIST_IDENTITY_JAVA, "java");
        Verdict python = grader.grade(roundTrip, LIST_IDENTITY_PYTHON, "python");

        assertThat(java.outcome()).isEqualTo(Verdict.Outcome.FAILED);
        assertThat(java.passed()).isZero();
        assertThat(python.outcome()).isEqualTo(Verdict.Outcome.FAILED);
        assertThat(python.passed()).isZero();
    }

    // --- Shape generation ---------------------------------------------------------

    /**
     * Lists as their serialised arrays, so the expected value is the input itself -
     * except for the two spellings of empty, whose canonical serialised form is
     * {@code []} either way.
     */
    private static List<TestCase> listShapes() {
        Random random = new Random(SEED);
        List<TestCase> cases = new ArrayList<>();
        cases.add(new TestCase(json("[[]]"), json("[]")));
        cases.add(new TestCase(json("[null]"), json("[]")));
        for (int i = 0; i < GENERATED_SHAPES; i++) {
            ArrayNode values = MAPPER.createArrayNode();
            for (int n = 0; n < random.nextInt(1, 12); n++) {
                values.add(random.nextInt(-50, 50));
            }
            cases.add(new TestCase(MAPPER.createArrayNode().add(values), values));
        }
        return cases;
    }

    /**
     * Trees as their canonical level-order arrays. Generated by the same walk the
     * serialisation is defined by - expand each non-null entry into its two children,
     * then trim trailing nulls - so the expected value is the input itself, without this
     * test re-implementing the deserialiser it is checking.
     */
    private static List<TestCase> treeShapes() {
        Random random = new Random(SEED);
        List<TestCase> cases = new ArrayList<>();
        cases.add(new TestCase(json("[[]]"), json("[]")));
        cases.add(new TestCase(json("[null]"), json("[]")));
        cases.add(new TestCase(json("[[1, null, 2]]"), json("[1, null, 2]")));
        cases.add(new TestCase(json("[[3, 9, 20, null, null, 15, 7]]"), json("[3, 9, 20, null, null, 15, 7]")));
        for (int i = 0; i < GENERATED_SHAPES; i++) {
            ArrayNode tree = randomTree(random, random.nextInt(1, 14));
            cases.add(new TestCase(MAPPER.createArrayNode().add(tree), tree));
        }
        return cases;
    }

    private static ArrayNode randomTree(Random random, int nodeBudget) {
        ArrayNode levelOrder = MAPPER.createArrayNode();
        levelOrder.add(random.nextInt(-50, 50));
        int remaining = nodeBudget - 1;
        for (int i = 0; i < levelOrder.size(); i++) {
            if (levelOrder.get(i).isNull()) {
                continue;
            }
            for (int child = 0; child < 2; child++) {
                if (remaining > 0 && random.nextInt(100) < 65) {
                    levelOrder.add(random.nextInt(-50, 50));
                    remaining--;
                } else {
                    levelOrder.addNull();
                }
            }
        }
        return trimTrailingNulls(levelOrder);
    }

    private static ArrayNode trimTrailingNulls(ArrayNode levelOrder) {
        int end = levelOrder.size();
        while (end > 0 && levelOrder.get(end - 1).isNull()) {
            end--;
        }
        ArrayNode trimmed = MAPPER.createArrayNode();
        for (int i = 0; i < end; i++) {
            trimmed.add(levelOrder.get(i));
        }
        return trimmed;
    }

    /** An exercise whose one parameter and return type are the given linked structure. */
    private static Exercise identityExercise(DataType type, List<TestCase> cases) {
        Signature signature =
                new Signature("identity", List.of(new Parameter("value", type)), type);
        return new Exercise(
                "round-trip-" + type,
                "Round Trip",
                "Return the argument unchanged.",
                "algorithms",
                List.of("demo"),
                Difficulty.EASY,
                Form.CHALLENGE,
                new Response.Code(signature),
                new Grading.TestCases(Comparison.exact(), cases),
                List.of());
    }

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException("Bad test JSON: " + raw, e);
        }
    }
}
