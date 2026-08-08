package com.sweprep.backend.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.ExerciseCatalog;
import com.sweprep.backend.exercise.Grading;
import com.sweprep.backend.exercise.Response;
import com.sweprep.backend.grader.AnswerKeyGrader;
import com.sweprep.backend.grader.GraderRegistry;
import com.sweprep.backend.grader.TestCaseGrader;
import com.sweprep.backend.grader.Verdict;
import com.sweprep.backend.language.JavaLanguageAdapter;
import com.sweprep.backend.runner.LocalJavaRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Grades the real seeded exercises against their reference solutions, end to end,
 * to prove the criterion that at least three real problems are solvable through
 * the flow. It never commits any content: it reads a local clone of the private
 * content repo and is <em>skipped</em> when none is present (as in CI), so it is a
 * repeatable local proof rather than a committed one.
 *
 * <p>Point it at a clone with {@code -Dsweprep.content.path=…} or
 * {@code SWEPREP_CONTENT_PATH=…}; it defaults to {@code ../content}.
 */
class RealContentSmokeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static Path contentDir() {
        String configured = System.getProperty("sweprep.content.path");
        if (configured == null) {
            configured = System.getenv("SWEPREP_CONTENT_PATH");
        }
        return Path.of(configured == null ? "../content" : configured);
    }

    private GraderRegistry graders() {
        TestCaseGrader testCases = new TestCaseGrader(
                new JavaLanguageAdapter(), new LocalJavaRunner(), mapper, Duration.ofSeconds(10));
        return new GraderRegistry(List.of(testCases, new AnswerKeyGrader(mapper)));
    }

    @Test
    void everyRealCodingExerciseSolvesWithItsReferenceSolution() throws Exception {
        Path dir = contentDir();
        assumeTrue(Files.isDirectory(dir), "no local content clone at " + dir.toAbsolutePath());

        ExerciseCatalog catalog = new FileExerciseCatalog(new ContentProperties(dir.toString()), mapper);
        GraderRegistry graders = graders();

        List<Exercise> coding = catalog.all().stream()
                .filter(e -> e.grading() instanceof Grading.TestCases)
                .toList();
        assertThat(coding).as("at least three real coding problems").hasSizeGreaterThanOrEqualTo(3);

        for (Exercise exercise : coding) {
            Path solution = dir.resolve("solutions").resolve(exercise.id() + ".java");
            assumeTrue(Files.exists(solution), "no reference solution for " + exercise.id());
            Verdict verdict = graders.grade(exercise, Files.readString(solution));
            assertThat(verdict.outcome())
                    .as("reference solution for %s should pass every case", exercise.id())
                    .isEqualTo(Verdict.Outcome.PASSED);
        }
    }

    /**
     * The set-level answer-tell guard (issue #60) run over the <em>real</em> content set.
     * It lives here, beside the other real-content proofs, precisely because this test is
     * <b>skipped when no content clone is present</b> (as on CI) - so an author with a
     * local clone sees the finding and fixes it in the content repo, while CI never turns
     * the expected finding into a permanently red build. The check itself is a quality
     * smell, deliberately not a load failure: a malformed file still fails at load, a
     * merely lopsided one surfaces here. The mechanism is demonstrated on both sides by
     * {@link AnswerTellCheckerTest}'s fixtures, independent of what real content looks like.
     *
     * <p>Expect this to fail on the first authored AI/ML batch until that content repo's own
     * PR pads the distractors up to parity - that failure is the guard working, not a bug
     * in it. Do not silence it by weakening the check.
     */
    @Test
    void realChoiceChecksHaveNoSetLevelAnswerTells() {
        Path dir = contentDir();
        assumeTrue(Files.isDirectory(dir), "no local content clone at " + dir.toAbsolutePath());

        ExerciseCatalog catalog = new FileExerciseCatalog(new ContentProperties(dir.toString()), mapper);
        List<AnswerTellChecker.Finding> findings =
                new AnswerTellChecker(mapper).checkAll(catalog.all());

        assertThat(findings)
                .as(
                        "real multiple-choice checks must not carry set-level answer tells "
                                + "(issue #60). Findings:%n%s",
                        findings.stream()
                                .map(AnswerTellChecker.Finding::message)
                                .reduce("", (a, b) -> a + "  - " + b + System.lineSeparator()))
                .isEmpty();
    }

    @Test
    void theConceptExerciseIsGradedWithNoRunner() {
        Path dir = contentDir();
        assumeTrue(Files.isDirectory(dir), "no local content clone at " + dir.toAbsolutePath());

        ExerciseCatalog catalog = new FileExerciseCatalog(new ContentProperties(dir.toString()), mapper);
        GraderRegistry graders = graders();

        Exercise concept = catalog.all().stream()
                .filter(e -> e.grading() instanceof Grading.AnswerKey)
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a concept (answer-key) exercise"));
        assertThat(concept.response()).isInstanceOf(Response.Choice.class);

        List<String> options = ((Response.Choice) concept.response()).optionTexts();
        String correct = ((Grading.AnswerKey) concept.grading()).expected().asText();

        assertThat(graders.grade(concept, correct).outcome()).isEqualTo(Verdict.Outcome.PASSED);
        String wrong = options.stream().filter(o -> !o.equals(correct)).findFirst().orElseThrow();
        assertThat(graders.grade(concept, wrong).outcome()).isEqualTo(Verdict.Outcome.FAILED);
    }
}
