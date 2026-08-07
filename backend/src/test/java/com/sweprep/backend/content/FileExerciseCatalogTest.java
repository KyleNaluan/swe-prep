package com.sweprep.backend.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sweprep.backend.exercise.Content;
import com.sweprep.backend.exercise.ContentCatalog;
import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.ExerciseCatalog;
import com.sweprep.backend.exercise.Family;
import com.sweprep.backend.exercise.Grading;
import com.sweprep.backend.exercise.Hint;
import com.sweprep.backend.exercise.Lesson;
import com.sweprep.backend.exercise.Response;
import com.sweprep.backend.exercise.Stability;
import java.io.IOException;
import java.time.LocalDate;
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

    private static final String EXPLAIN_EXERCISE =
            """
            {
              "id": "explain-demo", "title": "Explain", "statement": "Explain it.",
              "domain": "fundamentals", "topics": ["demo"],
              "difficulty": "EASY", "form": "REP",
              "response": { "kind": "freeText" },
              "grading": { "kind": "selfCheck", "modelAnswer": "The model answer." },
              "family": ["AIML"], "stability": "VOLATILE", "reviewed": "2026-08-07"
            }
            """;

    private static final String LESSON =
            """
            {
              "kind": "lesson",
              "id": "concept-message-queue",
              "title": "Message queues, and when to reach for one",
              "statement": "A message queue decouples a producer from a consumer.",
              "domain": "fundamentals", "topics": ["messaging", "architecture"],
              "difficulty": "EASY",
              "checks": ["mq-when-to-use", "mq-vs-direct-call"],
              "family": ["BACKEND"], "stability": "VOLATILE", "reviewed": "2026-08-07"
            }
            """;

    private static final String LESSON_NO_TAGS =
            """
            {
              "kind": "lesson",
              "id": "concept-message-queue",
              "title": "Message queues, and when to reach for one",
              "statement": "A message queue decouples a producer from a consumer.",
              "domain": "fundamentals", "topics": ["messaging", "architecture"],
              "difficulty": "EASY",
              "checks": ["mq-when-to-use", "mq-vs-direct-call"]
            }
            """;

    private FileExerciseCatalog catalog(Path dir) {
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
    void loadsAFreeTextSelfCheckExerciseWithItsContentTags(@TempDir Path dir) throws IOException {
        write(dir, "explain.json", EXPLAIN_EXERCISE);

        Exercise loaded = catalog(dir).byId("explain-demo").orElseThrow();

        assertThat(loaded.response()).isInstanceOf(Response.FreeText.class);
        assertThat(loaded.grading()).isInstanceOfSatisfying(
                Grading.SelfCheck.class,
                selfCheck -> assertThat(selfCheck.modelAnswer()).isEqualTo("The model answer."));
        assertThat(loaded.family()).containsExactly(Family.AIML);
        assertThat(loaded.stability()).isEqualTo(Stability.VOLATILE);
        assertThat(loaded.reviewed()).isEqualTo(LocalDate.of(2026, 8, 7));
    }

    @Test
    void anExerciseWithNoContentTagsDefaultsToStableAndUntagged(@TempDir Path dir) throws IOException {
        write(dir, "pick.json", CHOICE_EXERCISE);

        Exercise loaded = catalog(dir).byId("pick-demo").orElseThrow();

        assertThat(loaded.family()).isEmpty();
        assertThat(loaded.stability()).isEqualTo(Stability.STABLE);
        assertThat(loaded.reviewed()).isNull();
    }

    @Test
    void anUnknownFamilyNamesFileAndField(@TempDir Path dir) throws IOException {
        String badFamily = CHOICE_EXERCISE.replaceFirst(
                "\\}\\s*$",
                """
                ,
                  "family": ["NONESUCH"]
                }
                """);
        write(dir, "bad-family.json", badFamily);

        assertThatThrownBy(catalog(dir)::all)
                .isInstanceOf(ContentException.class)
                .hasMessageContaining("bad-family.json")
                .hasMessageContaining("family")
                .hasMessageContaining("NONESUCH");
    }

    @Test
    void aMalformedReviewedDateNamesFileAndField(@TempDir Path dir) throws IOException {
        String badReviewed = CHOICE_EXERCISE.replaceFirst(
                "\\}\\s*$",
                """
                ,
                  "reviewed": "last tuesday"
                }
                """);
        write(dir, "bad-reviewed.json", badReviewed);

        assertThatThrownBy(catalog(dir)::all)
                .isInstanceOf(ContentException.class)
                .hasMessageContaining("bad-reviewed.json")
                .hasMessageContaining("reviewed");
    }

    @Test
    void aSelfCheckMissingItsModelAnswerNamesFileAndField(@TempDir Path dir) throws IOException {
        write(dir, "no-model.json",
                EXPLAIN_EXERCISE.replace("\"modelAnswer\": \"The model answer.\"", "\"nope\": \"x\""));

        assertThatThrownBy(catalog(dir)::all)
                .isInstanceOf(ContentException.class)
                .hasMessageContaining("no-model.json")
                .hasMessageContaining("modelAnswer");
    }

    @Test
    void parsesAnOrderedHintLadderWhenPresent(@TempDir Path dir) throws IOException {
        String withHints = CODE_EXERCISE.replaceFirst(
                "\\}\\s*$",
                """
                ,
                  "hints": [
                    { "name": "Pattern", "body": "It is just an echo." },
                    { "name": "Approach", "body": "Return the argument unchanged." }
                  ]
                }
                """);
        write(dir, "echo.json", withHints);

        Exercise loaded = catalog(dir).byId("echo-demo").orElseThrow();
        assertThat(loaded.hints()).extracting(Hint::name).containsExactly("Pattern", "Approach");
        assertThat(loaded.hints()).extracting(Hint::body)
                .containsExactly("It is just an echo.", "Return the argument unchanged.");
    }

    @Test
    void anExerciseWithNoHintsLoadsWithAnEmptyLadder(@TempDir Path dir) throws IOException {
        write(dir, "echo.json", CODE_EXERCISE);

        assertThat(catalog(dir).byId("echo-demo").orElseThrow().hints()).isEmpty();
    }

    @Test
    void parsesACheckExplanationWhenPresent(@TempDir Path dir) throws IOException {
        String withExplanation = CHOICE_EXERCISE.replaceFirst(
                "\\}\\s*$",
                """
                ,
                  "explanation": "B is correct because it is the only stable option."
                }
                """);
        write(dir, "pick.json", withExplanation);

        Exercise loaded = catalog(dir).byId("pick-demo").orElseThrow();
        assertThat(loaded.explanation())
                .isEqualTo("B is correct because it is the only stable option.");
    }

    @Test
    void aCheckWithNoExplanationLoadsWithNull(@TempDir Path dir) throws IOException {
        write(dir, "pick.json", CHOICE_EXERCISE);

        assertThat(catalog(dir).byId("pick-demo").orElseThrow().explanation()).isNull();
    }

    @Test
    void aBlankExplanationNamesFileAndField(@TempDir Path dir) throws IOException {
        String blankExplanation = CHOICE_EXERCISE.replaceFirst(
                "\\}\\s*$",
                """
                ,
                  "explanation": "   "
                }
                """);
        write(dir, "bad-explanation.json", blankExplanation);

        assertThatThrownBy(catalog(dir)::all)
                .isInstanceOf(ContentException.class)
                .hasMessageContaining("bad-explanation.json")
                .hasMessageContaining("explanation");
    }

    @Test
    void aMalformedHintRungNamesFileAndField(@TempDir Path dir) throws IOException {
        String badHints = CODE_EXERCISE.replaceFirst(
                "\\}\\s*$",
                """
                ,
                  "hints": [ { "name": "Pattern" } ]
                }
                """);
        write(dir, "bad-hint.json", badHints);

        assertThatThrownBy(catalog(dir)::all)
                .isInstanceOf(ContentException.class)
                .hasMessageContaining("bad-hint.json")
                .hasMessageContaining("body");
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
                .hasMessageContaining("No content");
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
                .hasMessageContaining("Duplicate content id")
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

    @Test
    void loadsALessonByItsKindDiscriminatorWithChecksAndTags(@TempDir Path dir) throws IOException {
        write(dir, "mq.json", LESSON);

        Content loaded = catalog(dir).contentById("concept-message-queue").orElseThrow();

        assertThat(loaded).isInstanceOfSatisfying(Lesson.class, lesson -> {
            assertThat(lesson.statement())
                    .isEqualTo("A message queue decouples a producer from a consumer.");
            assertThat(lesson.checks()).containsExactly("mq-when-to-use", "mq-vs-direct-call");
            assertThat(lesson.family()).containsExactly(Family.BACKEND);
            assertThat(lesson.stability()).isEqualTo(Stability.VOLATILE);
            assertThat(lesson.reviewed()).isEqualTo(LocalDate.of(2026, 8, 7));
        });
    }

    @Test
    void aLessonIsNeverServedThroughTheExerciseView(@TempDir Path dir) throws IOException {
        write(dir, "mq.json", LESSON);
        write(dir, "pick.json", CHOICE_EXERCISE);

        FileExerciseCatalog catalog = catalog(dir);

        // The wide content view sees both kinds...
        assertThat(catalog.allContent()).extracting(Content::id)
                .containsExactlyInAnyOrder("concept-message-queue", "pick-demo");
        // ...but the exercise view - the seam that attempts and grades - sees only the exercise,
        // so a lesson can never be started, graded, or produce a verdict.
        assertThat(catalog.all()).extracting(Exercise::id).containsExactly("pick-demo");
        assertThat(catalog.byId("concept-message-queue")).isEmpty();
    }

    @Test
    void aLessonWithNoContentTagsDefaultsToStableAndUntagged(@TempDir Path dir) throws IOException {
        write(dir, "mq.json", LESSON_NO_TAGS);

        Lesson loaded = (Lesson) catalog(dir).contentById("concept-message-queue").orElseThrow();

        assertThat(loaded.family()).isEmpty();
        assertThat(loaded.stability()).isEqualTo(Stability.STABLE);
        assertThat(loaded.reviewed()).isNull();
    }

    @Test
    void aMalformedLessonNamesFileAndFieldAsAnExerciseDoes(@TempDir Path dir) throws IOException {
        write(dir, "no-statement.json", LESSON.replace("\"statement\"", "\"nope\""));

        assertThatThrownBy(catalog(dir)::allContent)
                .isInstanceOf(ContentException.class)
                .hasMessageContaining("no-statement.json")
                .hasMessageContaining("lesson")
                .hasMessageContaining("statement");
    }

    @Test
    void aLessonMissingItsChecksNamesFileAndField(@TempDir Path dir) throws IOException {
        write(dir, "no-checks.json", LESSON.replace("\"checks\"", "\"nope\""));

        assertThatThrownBy(catalog(dir)::allContent)
                .isInstanceOf(ContentException.class)
                .hasMessageContaining("no-checks.json")
                .hasMessageContaining("checks");
    }

    @Test
    void anUnknownKindNamesFileAndField(@TempDir Path dir) throws IOException {
        write(dir, "quiz.json", LESSON.replace("\"lesson\"", "\"quiz\""));

        assertThatThrownBy(catalog(dir)::allContent)
                .isInstanceOf(ContentException.class)
                .hasMessageContaining("quiz.json")
                .hasMessageContaining("kind")
                .hasMessageContaining("quiz");
    }

    @Test
    void anExerciseFileWithNoKindStillLoadsAsAnExercise(@TempDir Path dir) throws IOException {
        // CODE_EXERCISE carries no "kind" field; the default keeps it loading unchanged.
        write(dir, "echo.json", CODE_EXERCISE);

        assertThat(catalog(dir).contentById("echo-demo")).get().isInstanceOf(Exercise.class);
    }
}
