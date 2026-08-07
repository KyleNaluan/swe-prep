package com.sweprep.backend.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.ExerciseCatalog;
import com.sweprep.backend.exercise.Grading;
import com.sweprep.backend.exercise.Response;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves content loads from a local directory and that every failure - missing
 * path, malformed file, missing field, duplicate id - is reported as a clear
 * {@link ContentException} (issue #14's first acceptance criterion). All content
 * here is synthetic and written at runtime, so nothing is committed to the repo.
 */
class FileExerciseCatalogTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static final String CODE_EXERCISE =
            """
            {
              "id": "echo-demo", "title": "Echo", "statement": "Return the argument.",
              "domain": "algorithms", "topics": ["demo"],
              "difficulty": "EASY", "form": "CHALLENGE",
              "response": { "kind": "code", "signature": {
                "method": "echo",
                "parameters": [ { "name": "n", "type": "INT" } ],
                "returns": "INT" } },
              "grading": { "kind": "testCases", "comparison": "exact",
                "cases": [ { "input": [3], "expected": 3 } ] }
            }
            """;

    private static final String CHOICE_EXERCISE =
            """
            {
              "id": "pick-demo", "title": "Pick", "statement": "Pick B.",
              "domain": "fundamentals", "topics": ["demo"],
              "difficulty": "EASY", "form": "REP",
              "response": { "kind": "choice", "options": ["A", "B"] },
              "grading": { "kind": "answerKey", "comparison": "exact", "expected": "B" }
            }
            """;

    private ExerciseCatalog catalog(Path dir) {
        return new FileExerciseCatalog(new ContentProperties(dir.toString()), mapper);
    }

    private static void write(Path dir, String name, String json) throws IOException {
        Files.writeString(dir.resolve(name), json);
    }

    @Test
    void loadsEveryJsonExerciseInTheDirectory(@TempDir Path dir) throws IOException {
        write(dir, "echo.json", CODE_EXERCISE);
        write(dir, "pick.json", CHOICE_EXERCISE);

        ExerciseCatalog catalog = catalog(dir);

        assertThat(catalog.all()).extracting(Exercise::id)
                .containsExactlyInAnyOrder("echo-demo", "pick-demo");
        assertThat(catalog.byId("echo-demo")).get()
                .extracting(Exercise::response).isInstanceOf(Response.Code.class);
        assertThat(catalog.byId("pick-demo")).get()
                .extracting(Exercise::grading).isInstanceOf(Grading.AnswerKey.class);
        assertThat(catalog.byId("missing")).isEmpty();
    }

    @Test
    void aMissingDirectoryIsAClearError(@TempDir Path dir) {
        ExerciseCatalog catalog = catalog(dir.resolve("not-cloned-yet"));

        assertThatThrownBy(catalog::all)
                .isInstanceOf(ContentException.class)
                .hasMessageContaining("Content directory not found")
                .hasMessageContaining("swe-prep-content");
    }

    @Test
    void aContentPathThatIsAFileIsAClearError(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("content.json");
        Files.writeString(file, CODE_EXERCISE);

        assertThatThrownBy(catalog(file)::all)
                .isInstanceOf(ContentException.class)
                .hasMessageContaining("not a directory");
    }

    @Test
    void anEmptyDirectoryIsAClearError(@TempDir Path dir) {
        assertThatThrownBy(catalog(dir)::all)
                .isInstanceOf(ContentException.class)
                .hasMessageContaining("No exercise");
    }

    @Test
    void malformedJsonNamesTheFile(@TempDir Path dir) throws IOException {
        write(dir, "broken.json", "{ not valid json ");

        assertThatThrownBy(catalog(dir)::all)
                .isInstanceOf(ContentException.class)
                .hasMessageContaining("broken.json")
                .hasMessageContaining("not valid JSON");
    }

    @Test
    void aMissingRequiredFieldNamesFileAndField(@TempDir Path dir) throws IOException {
        write(dir, "no-grading.json", CODE_EXERCISE.replace("\"grading\"", "\"nope\""));

        assertThatThrownBy(catalog(dir)::all)
                .isInstanceOf(ContentException.class)
                .hasMessageContaining("no-grading.json")
                .hasMessageContaining("grading");
    }

    @Test
    void anUnknownEnumValueIsAClearError(@TempDir Path dir) throws IOException {
        write(dir, "bad-difficulty.json", CODE_EXERCISE.replace("EASY", "TRIVIAL"));

        assertThatThrownBy(catalog(dir)::all)
                .isInstanceOf(ContentException.class)
                .hasMessageContaining("difficulty")
                .hasMessageContaining("TRIVIAL");
    }

    @Test
    void aDuplicateIdIsAClearError(@TempDir Path dir) throws IOException {
        write(dir, "one.json", CODE_EXERCISE);
        write(dir, "two.json", CODE_EXERCISE);

        assertThatThrownBy(catalog(dir)::all)
                .isInstanceOf(ContentException.class)
                .hasMessageContaining("Duplicate exercise id")
                .hasMessageContaining("echo-demo");
    }

    @Test
    void aFailedLoadIsNotCachedSoCloningLaterWorksWithoutRestart(@TempDir Path dir) throws IOException {
        Path contentDir = dir.resolve("content");
        ExerciseCatalog catalog = catalog(contentDir);

        // First access before the content exists: fails.
        assertThatThrownBy(catalog::all).isInstanceOf(ContentException.class);

        // The content appears (as if cloned); the next access succeeds.
        Files.createDirectories(contentDir);
        write(contentDir, "echo.json", CODE_EXERCISE);

        assertThat(catalog.all()).extracting(Exercise::id).containsExactly("echo-demo");
    }
}
