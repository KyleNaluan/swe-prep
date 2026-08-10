package com.sweprep.backend.commit;

import static org.assertj.core.api.Assertions.assertThat;

import com.sweprep.backend.exercise.Comparison;
import com.sweprep.backend.exercise.Difficulty;
import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.Form;
import com.sweprep.backend.exercise.Grading;
import com.sweprep.backend.exercise.Option;
import com.sweprep.backend.exercise.Response;
import com.sweprep.backend.exercise.Stability;
import com.sweprep.backend.testsupport.Fixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.StreamSupport;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Solution auto-commit (issue #22, decision issue #7 item 1), proven against a real
 * throwaway git repository - never the real remote. {@code origin} is a local bare
 * repo fixture created fresh per test, so {@link SolutionCommitService#commitSolution}
 * genuinely commits and pushes, exactly as it would in production, without any network
 * call or real GitHub repo ever being involved - "testable without pushing... against a
 * local bare repo fixture", per the ticket.
 */
class SolutionCommitServiceTest {

    @TempDir
    Path tempDir;

    private Path bareOrigin;
    private Path clone;

    @BeforeEach
    void setUp() throws Exception {
        bareOrigin = tempDir.resolve("origin.git");
        clone = tempDir.resolve("clone");
        Git.init().setBare(true).setDirectory(bareOrigin.toFile()).call().close();

        try (Git git = Git.init().setDirectory(clone.toFile()).call()) {
            git.getRepository().getConfig().setString("remote", "origin", "url", bareOrigin.toString());
            git.getRepository().getConfig().save();
            // A seed commit so the branch exists before the service's own commit lands.
            Files.writeString(clone.resolve("README.md"), "seed");
            git.add().addFilepattern("README.md").call();
            git.commit()
                    .setMessage("seed")
                    .setAuthor(new PersonIdent("Test", "test@example.com"))
                    .call();
        }
    }

    @Test
    void commitsAndPushesASolvedCodeExercise() throws Exception {
        SolutionCommitService service = serviceFor(properties(true, true));
        Exercise exercise = Fixtures.pairInAnyOrder();

        SolutionCommitResult result = service.commitSolution(exercise, Fixtures.PAIR_SOLUTION);

        assertThat(result.committed()).isTrue();
        assertThat(result.reason()).isNull();
        assertThat(clone.resolve("learner-solutions/pair-in-any-order.java")).exists();

        // Pushed to the bare origin, not merely committed locally - the whole point.
        try (Git bare = Git.open(bareOrigin.toFile())) {
            List<RevCommit> commits = toList(bare.log().call());
            assertThat(commits).anyMatch(c -> c.getFullMessage().contains("pair-in-any-order"));
        }
    }

    @Test
    void neverCommitsAChoiceExerciseNoSolutionArtifactExists() {
        SolutionCommitService service = serviceFor(properties(true, true));
        Exercise choiceExercise = new Exercise(
                "choice-ex",
                "A choice exercise",
                "Pick one.",
                "fundamentals",
                List.of("demo"),
                Difficulty.EASY,
                Form.REP,
                new Response.Choice(List.of(Option.correct("A"))),
                new Grading.AnswerKey(Fixtures.MAPPER.getNodeFactory().textNode("A"), Comparison.exact()),
                List.of(),
                null,
                List.of(),
                Stability.STABLE,
                null,
                null);

        SolutionCommitResult result = service.commitSolution(choiceExercise, "A");

        assertThat(result.committed()).isFalse();
        assertThat(clone.resolve("learner-solutions")).doesNotExist();
    }

    @Test
    void resubmittingByteIdenticalCodeMakesNoSecondCommit() {
        SolutionCommitService service = serviceFor(properties(true, false));
        Exercise exercise = Fixtures.pairInAnyOrder();

        SolutionCommitResult first = service.commitSolution(exercise, Fixtures.PAIR_SOLUTION);
        SolutionCommitResult second = service.commitSolution(exercise, Fixtures.PAIR_SOLUTION);

        assertThat(first.committed()).isTrue();
        assertThat(second.committed()).isFalse();
        assertThat(second.reason()).contains("unchanged");
    }

    @Test
    void disablingAutoCommitSkipsEntirely() {
        SolutionCommitProperties disabled =
                new SolutionCommitProperties(false, clone.toString(), false, null, null, null);
        SolutionCommitService service = new SolutionCommitService(disabled);
        Exercise exercise = Fixtures.pairInAnyOrder();

        SolutionCommitResult result = service.commitSolution(exercise, Fixtures.PAIR_SOLUTION);

        assertThat(result.committed()).isFalse();
        assertThat(clone.resolve("learner-solutions")).doesNotExist();
    }

    @Test
    void aMissingCloneDegradesToSkippedRatherThanThrowing() {
        SolutionCommitProperties properties = new SolutionCommitProperties(
                true, tempDir.resolve("no-such-clone").toString(), false, null, null, null);
        SolutionCommitService service = new SolutionCommitService(properties);
        Exercise exercise = Fixtures.pairInAnyOrder();

        SolutionCommitResult result = service.commitSolution(exercise, Fixtures.PAIR_SOLUTION);

        assertThat(result.committed()).isFalse();
        assertThat(result.reason()).isNotBlank();
    }

    private SolutionCommitService serviceFor(SolutionCommitProperties properties) {
        return new SolutionCommitService(properties);
    }

    private SolutionCommitProperties properties(boolean enabled, boolean push) {
        return new SolutionCommitProperties(
                enabled, clone.toString(), push, null, "Test Author", "test@example.com");
    }

    private static List<RevCommit> toList(Iterable<RevCommit> commits) {
        return StreamSupport.stream(commits.spliterator(), false).toList();
    }
}
