package com.sweprep.backend.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sweprep.backend.authoring.AuthorContentCli.AuthorResult;
import com.sweprep.backend.content.ContentProperties;
import com.sweprep.backend.content.FileExerciseCatalog;
import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.ExerciseCatalog;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end tests for the whole content-entry flow (issue #24), exercised
 * exactly the way an author would run it: parse a problem spec, derive, review,
 * gate on acceptance, write. Every acceptance criterion is asserted here at
 * least once, on top of the focused unit tests in the rest of this package:
 *
 * <ul>
 *   <li>#1 "a single command... produces a complete content entry" - {@link
 *       #producesAllFilesOnAcceptance}
 *   <li>#3 "presented for human verification before being accepted" -
 *       {@link #decliningTheReviewWritesNothing} and {@link
 *       #acceptingTheReviewWritesTheFiles}: the same input produces zero files
 *       or the full set purely based on the human's answer at the prompt printed
 *       from {@link #producesAllFilesOnAcceptance}'s review
 *   <li>#5 "refuses to write anything into this public repository" -
 *       {@link #refusesADestinationInsideThePublicRepo}
 * </ul>
 *
 * All content is synthetic and written only under {@code @TempDir}, never
 * committed (issue #4/#14).
 */
class AuthorContentCliTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static final String SPEC_JSON =
            """
            {
              "id": "demo-running-max",
              "title": "Running Max",
              "statement": "Given a non-empty array of integers, return its maximum value.",
              "domain": "algorithms",
              "topics": ["array", "two-pointers"],
              "difficulty": "EASY",
              "signature": {
                "method": "runningMax",
                "parameters": [ { "name": "nums", "type": "INT_ARRAY" } ],
                "returns": "INT"
              },
              "comparison": "exact",
              "cases": [
                { "input": [[1, 5, 3]], "expected": 5 },
                { "input": [[-4, -1, -9]], "expected": -1 },
                { "input": [[7]], "expected": 7 },
                { "input": [[2, 2, 2]], "expected": 2 }
              ],
              "referenceSolution": "class Solution {\\n    public int runningMax(int[] nums) {\\n        int max = nums[0];\\n        for (int i = 1; i < nums.length; i++) {\\n            if (nums[i] > max) {\\n                max = nums[i];\\n            }\\n        }\\n        return max;\\n    }\\n}\\n",
              "explanation": "A single pass keeps the largest value seen so far."
            }
            """;

    private ProblemSpec parseSpec() throws Exception {
        return ProblemSpecParser.parse("test", mapper.readTree(SPEC_JSON));
    }

    @Test
    void decliningTheReviewWritesNothing(@TempDir Path contentDir) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        BufferedReader stdin = new BufferedReader(new StringReader("n\n"));

        AuthorResult result = AuthorContentCli.author(parseSpec(), contentDir, false, out, stdin);

        assertThat(result.accepted()).isFalse();
        assertThat(result.writtenFiles()).isEmpty();
        try (var files = Files.list(contentDir)) {
            assertThat(files.toList()).isEmpty();
        }
        // The review must still have been printed before the human could decide.
        assertThat(buffer.toString(StandardCharsets.UTF_8)).contains("CHALLENGE: demo-running-max");
        assertThat(buffer.toString(StandardCharsets.UTF_8)).contains("Accept and write this content entry?");
    }

    @Test
    void acceptingTheReviewWritesTheFiles(@TempDir Path contentDir) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        BufferedReader stdin = new BufferedReader(new StringReader("y\n"));

        AuthorResult result = AuthorContentCli.author(parseSpec(), contentDir, false, out, stdin);

        assertThat(result.accepted()).isTrue();
        assertThat(result.writtenFiles()).isNotEmpty();
        assertThat(Files.exists(contentDir.resolve("demo-running-max.json"))).isTrue();
        assertThat(Files.exists(contentDir.resolve("solutions").resolve("demo-running-max.java"))).isTrue();
    }

    @Test
    void producesAllFilesOnAcceptance(@TempDir Path contentDir) throws Exception {
        AuthorResult result = author(contentDir, true);

        List<String> ids = result.writtenFiles().stream()
                .map(p -> p.getFileName().toString())
                .filter(name -> name.endsWith(".json"))
                .toList();
        assertThat(ids).containsExactlyInAnyOrder(
                "demo-running-max.json",
                "demo-running-max-pattern.json",
                "demo-running-max-complexity.json",
                "demo-running-max-fill-blank.json",
                "demo-running-max-spot-bug.json",
                "demo-running-max-predict-output.json");
    }

    @Test
    void writtenContentLoadsBackThroughTheRealProductionLoader(@TempDir Path contentDir) throws Exception {
        author(contentDir, true);

        ExerciseCatalog catalog = new FileExerciseCatalog(new ContentProperties(contentDir.toString()), mapper);
        List<Exercise> all = catalog.all();

        assertThat(all).hasSize(6);
        assertThat(catalog.byId("demo-running-max")).isPresent();
        Exercise patternRep = catalog.byId("demo-running-max-pattern").orElseThrow();
        assertThat(patternRep.derivedFrom()).isNull();
        Exercise spotBugRep = catalog.byId("demo-running-max-spot-bug").orElseThrow();
        assertThat(spotBugRep.derivedFrom()).isEqualTo("demo-running-max");
    }

    @Test
    void yesFlagWritesWithoutReadingStdin(@TempDir Path contentDir) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        // An empty reader: if the flow tried to read a confirmation line, it would
        // read null/EOF and (per confirm()'s own logic) decline - so files landing
        // proves --yes truly bypassed the prompt rather than happening to read "y".
        BufferedReader emptyStdin = new BufferedReader(new StringReader(""));

        AuthorResult result = AuthorContentCli.author(parseSpec(), contentDir, true, out, emptyStdin);

        assertThat(result.accepted()).isTrue();
        assertThat(result.writtenFiles()).isNotEmpty();
    }

    @Test
    void refusesToOverwriteAnExistingFile(@TempDir Path contentDir) throws Exception {
        Files.writeString(contentDir.resolve("demo-running-max.json"), "{}");
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        BufferedReader stdin = new BufferedReader(new StringReader("y\n"));

        org.junit.jupiter.api.Assertions.assertThrows(
                AuthoringException.class,
                () -> AuthorContentCli.author(parseSpec(), contentDir, false, out, stdin));
    }

    @Test
    void refusesToOverwriteAnExistingSolutionFile(@TempDir Path contentDir) throws Exception {
        // A stale solutions/<id>.java from a prior partial run (whose .json is absent)
        // must still block the run rather than being silently clobbered.
        Files.createDirectories(contentDir.resolve("solutions"));
        Files.writeString(contentDir.resolve("solutions").resolve("demo-running-max.java"), "old");
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        BufferedReader stdin = new BufferedReader(new StringReader("y\n"));

        org.junit.jupiter.api.Assertions.assertThrows(
                AuthoringException.class,
                () -> AuthorContentCli.author(parseSpec(), contentDir, false, out, stdin));
        assertThat(Files.readString(contentDir.resolve("solutions").resolve("demo-running-max.java")))
                .isEqualTo("old");
    }

    @Test
    void refusesADestinationInsideThePublicRepo() throws Exception {
        Path insideThisRepo = Path.of("src").toAbsolutePath();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        BufferedReader stdin = new BufferedReader(new StringReader("y\n"));

        org.junit.jupiter.api.Assertions.assertThrows(
                AuthoringException.class,
                () -> AuthorContentCli.author(parseSpec(), insideThisRepo, true, out, stdin));
    }

    @Test
    void mainRunReturnsNonZeroExitCodeAndPrintsUsageOnBadArguments() {
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int exitCode = AuthorContentCli.run(
                new String[] {},
                new PrintStream(outBuf, true, StandardCharsets.UTF_8),
                new PrintStream(errBuf, true, StandardCharsets.UTF_8),
                new BufferedReader(new StringReader("")));

        assertThat(exitCode).isEqualTo(2);
        assertThat(errBuf.toString(StandardCharsets.UTF_8)).contains("--problem is required");
    }

    private AuthorResult author(Path contentDir, boolean autoAccept) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        BufferedReader stdin = new BufferedReader(new StringReader("y\n"));
        return AuthorContentCli.author(parseSpec(), contentDir, autoAccept, out, stdin);
    }
}
