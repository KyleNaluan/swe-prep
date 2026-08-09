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
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
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

    /**
     * The commit swe-prep-content was at right before the fix for the connective-style
     * regression this guards (issue #65's PR #5 red-team finding), and the four files
     * that batch added. Extracted at test time via {@code git show}, never committed
     * here - the public-engine/private-content split (issue #4) forbids checking any
     * problem content into this repo, even as a "known-bad" fixture, so the real batch
     * is read out of a local clone's history instead of a synthetic stand-in.
     */
    private static final String CONNECTIVE_REGRESSION_COMMIT =
            "9c73f984ed54e4a84336eea9b99d9c5c1f2e66a0";

    private static final List<String> CONNECTIVE_REGRESSION_FILES = List.of(
            "aiml-metrics-fbeta.json",
            "aiml-metrics-pr-baseline.json",
            "aiml-metrics-roc-auc-scores.json",
            "aiml-metrics-specificity.json");

    /**
     * The connective-style guard (issue #65), demonstrated against the exact real batch
     * that motivated it rather than a synthetic example - its acceptance criteria ask
     * for the real regression fixture, not a stand-in. Reads the four newly authored
     * AI/ML metrics checks from swe-prep-content PR #5 at {@link
     * #CONNECTIVE_REGRESSION_COMMIT}, the commit immediately before that content repo's
     * own fix: every distractor of at least one of those checks carried a "since" clause
     * the key did not, a clean exact-lexeme split (see {@code AnswerTellChecker}'s
     * javadoc on why the check reads one lexeme at a time rather than a merged
     * "some connective" bucket). Skipped, like every real-content test here, when no
     * local clone - or this specific historical commit - is available; a missing commit
     * is not treated as a content-repo history rewrite worth failing over.
     */
    @Test
    void theRealPr5BatchAtItsPreFixCommitTripsTheConnectiveStyleGuard() throws Exception {
        Path dir = contentDir();
        assumeTrue(Files.isDirectory(dir), "no local content clone at " + dir.toAbsolutePath());
        assumeTrue(Files.isDirectory(dir.resolve(".git")), dir + " is not a git working tree");
        assumeTrue(
                commitExists(dir, CONNECTIVE_REGRESSION_COMMIT),
                "local clone has no commit " + CONNECTIVE_REGRESSION_COMMIT);

        Path fixtureDir = Files.createTempDirectory("connective-regression");
        try {
            for (String file : CONNECTIVE_REGRESSION_FILES) {
                Files.writeString(
                        fixtureDir.resolve(file), gitShow(dir, CONNECTIVE_REGRESSION_COMMIT, file));
            }

            ExerciseCatalog catalog =
                    new FileExerciseCatalog(new ContentProperties(fixtureDir.toString()), mapper);
            List<AnswerTellChecker.Finding> findings =
                    new AnswerTellChecker(mapper).checkAll(catalog.all());

            assertThat(findings)
                    .as(
                            "the pre-fix PR #5 batch (commit %s) is the known-bad connective-style "
                                    + "regression fixture and must trip the guard",
                            CONNECTIVE_REGRESSION_COMMIT)
                    .extracting(AnswerTellChecker.Finding::tell)
                    .contains(AnswerTellChecker.Tell.CONNECTIVE_STYLE);
        } finally {
            deleteRecursively(fixtureDir);
        }
    }

    private static boolean commitExists(Path repo, String sha) throws Exception {
        Process process =
                new ProcessBuilder("git", "-C", repo.toString(), "cat-file", "-e", sha)
                        .redirectErrorStream(true)
                        .start();
        process.getInputStream().readAllBytes();
        return process.waitFor() == 0;
    }

    private static String gitShow(Path repo, String sha, String file) throws Exception {
        Process process =
                new ProcessBuilder("git", "-C", repo.toString(), "show", sha + ":" + file).start();
        String content = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git show failed for " + file + " at " + sha + ": " + error);
        }
        return content;
    }

    private static void deleteRecursively(Path dir) throws Exception {
        if (!Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (java.io.IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
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
