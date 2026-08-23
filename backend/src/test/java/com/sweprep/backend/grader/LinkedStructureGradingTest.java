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
import org.junit.jupiter.api.Test;

/**
 * The sample linked-list and binary-tree exercises graded end to end - parsed into a
 * {@link com.sweprep.backend.exercise.Signature}, the structure constructed from each
 * case's JSON, solved against an idiomatic {@code ListNode}/{@code TreeNode}, serialised
 * back and compared - in <em>both</em> languages, off the same untouched fixture.
 *
 * <p>That last part is the point: these are the same {@link Exercise} objects in both
 * halves of every test, never a Python-flavoured copy, so they demonstrate for linked
 * structures exactly what {@code TestCaseGraderTest}'s second-language test demonstrated
 * for arrays (issue #26) - a case is authored once and means the same thing everywhere.
 */
class LinkedStructureGradingTest {

    private final Exercise dropFirst = Fixtures.listDropFirst();
    private final Exercise dropRight = Fixtures.treeDropRight();

    private final Grader grader = new TestCaseGrader(
            new LanguageAdapterRegistry(List.of(new JavaLanguageAdapter(), new PythonLanguageAdapter())),
            new RunnerRegistry(List.of(new LocalJavaRunner(), new LocalPythonRunner("python3"))),
            new ObjectMapper(),
            Duration.ofSeconds(30));

    @Test
    void aLinkedListExerciseIsSolvedInJava() {
        Verdict verdict = grader.grade(dropFirst, Fixtures.LIST_DROP_FIRST_SOLUTION, "java");

        assertThat(verdict.outcome()).isEqualTo(Verdict.Outcome.PASSED);
        assertThat(verdict.passed()).isEqualTo(verdict.total());
        assertThat(verdict.total()).isEqualTo(5);
    }

    @Test
    void theSameLinkedListExerciseIsSolvedInPython() {
        Verdict verdict = grader.grade(dropFirst, Fixtures.LIST_DROP_FIRST_SOLUTION_PYTHON, "python");

        assertThat(verdict.outcome()).isEqualTo(Verdict.Outcome.PASSED);
        assertThat(verdict.passed()).isEqualTo(verdict.total());
        assertThat(verdict.total()).isEqualTo(5);
    }

    @Test
    void aBinaryTreeExerciseIsSolvedInJava() {
        Verdict verdict = grader.grade(dropRight, Fixtures.TREE_DROP_RIGHT_SOLUTION, "java");

        assertThat(verdict.outcome()).isEqualTo(Verdict.Outcome.PASSED);
        assertThat(verdict.passed()).isEqualTo(verdict.total());
        assertThat(verdict.total()).isEqualTo(6);
    }

    @Test
    void theSameBinaryTreeExerciseIsSolvedInPython() {
        Verdict verdict = grader.grade(dropRight, Fixtures.TREE_DROP_RIGHT_SOLUTION_PYTHON, "python");

        assertThat(verdict.outcome()).isEqualTo(Verdict.Outcome.PASSED);
        assertThat(verdict.passed()).isEqualTo(verdict.total());
        assertThat(verdict.total()).isEqualTo(6);
    }

    /**
     * A wrong answer is still counted case by case, so the serialised comparison is
     * genuinely doing the judging - the answer is compared, not merely the fact that
     * something came back. Dropping the <em>left</em> subtree instead of the right one
     * happens to be correct only where the two agree.
     */
    @Test
    void awrongTreeAnswerFailsTheCasesItGetsWrong() {
        String dropsTheWrongSide =
                """
                class Solution {
                    public TreeNode dropRight(TreeNode root) {
                        if (root == null) {
                            return null;
                        }
                        return new TreeNode(root.val, null, root.right);
                    }
                }
                """;

        Verdict verdict = grader.grade(dropRight, dropsTheWrongSide, "java");

        assertThat(verdict.outcome()).isEqualTo(Verdict.Outcome.FAILED);
        assertThat(verdict.passed()).isLessThan(verdict.total());
    }

    /**
     * The failing-case reveal (issues #16/#5) reads the same serialised forms, so a
     * solver who asks sees a linked structure written the way the statement writes it,
     * not an opaque object.
     */
    @Test
    void theRevealedFailingCaseCarriesTheSerialisedForms() {
        String alwaysEmpty =
                """
                class Solution {
                    public ListNode dropFirst(ListNode head) {
                        return null;
                    }
                }
                """;

        FailingCase failing = grader.firstFailingCase(dropFirst, alwaysEmpty, "java").orElseThrow();

        assertThat(failing.input().toString()).isEqualTo("[[1,2,3]]");
        assertThat(failing.expected().toString()).isEqualTo("[2,3]");
        assertThat(failing.actual().toString()).isEqualTo("[]");
    }
}
