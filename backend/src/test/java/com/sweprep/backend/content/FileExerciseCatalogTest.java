package com.sweprep.backend.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sweprep.backend.exercise.Comparison;
import com.sweprep.backend.exercise.Complexity;
import com.sweprep.backend.exercise.ComplexityCheck;
import com.sweprep.backend.exercise.Content;
import com.sweprep.backend.exercise.ContentCatalog;
import com.sweprep.backend.exercise.DataType;
import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.ExerciseCatalog;
import com.sweprep.backend.exercise.Family;
import com.sweprep.backend.exercise.Grading;
import com.sweprep.backend.exercise.Hint;
import com.sweprep.backend.exercise.InputGenerator;
import com.sweprep.backend.exercise.Lesson;
import com.sweprep.backend.exercise.Option;
import com.sweprep.backend.exercise.Response;
import com.sweprep.backend.exercise.SelfExplainPrompt;
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

    // The canonical passing choice: its one distractor names the misconception it targets
    // (issue #42), and the correct option is a plain string (which needs none). The mixed
    // string/object shape is deliberate - both are accepted.
    private static final String CHOICE_EXERCISE =
            """
            {
              "id": "pick-demo", "title": "Pick", "statement": "Pick B.",
              "domain": "fundamentals", "topics": ["demo"],
              "difficulty": "EASY", "form": "REP",
              "response": { "kind": "choice", "options": [
                { "text": "A", "misconception": "picks the first option without reading on" },
                "B" ] },
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

    // A code exercise with a scalable INT_ARRAY parameter and a fixed INT one, for the
    // complexity input-generator tests (issue #17).
    private static final String CODE_EXERCISE_WITH_ARRAY =
            """
            {
              "id": "sum-demo", "title": "Sum", "statement": "Sum the array up to target.",
              "domain": "algorithms", "topics": ["demo"],
              "difficulty": "EASY", "form": "CHALLENGE",
              "response": { "kind": "code", "signature": {
                "method": "sum",
                "parameters": [
                  { "name": "nums", "type": "INT_ARRAY" },
                  { "name": "target", "type": "INT" } ],
                "returns": "INT" } },
              "grading": { "kind": "testCases", "comparison": "exact",
                "cases": [ { "input": [[1, 2, 3], 5], "expected": 6 } ] }
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

    private static final String LESSON_WITH_PROMPTS =
            """
            {
              "kind": "lesson",
              "id": "concept-indexes",
              "title": "Why an index sometimes is not used",
              "statement": "A B-tree index speeds lookups by key.",
              "domain": "fundamentals", "topics": ["databases"],
              "difficulty": "MEDIUM",
              "checks": [],
              "prompts": [
                { "prompt": "Explain why a function-wrapped column skips the index.",
                  "modelAnswer": "The index stores raw values, not the function output." },
                { "prompt": "Predict the plan for a 90%-selectivity query.",
                  "modelAnswer": "A sequential scan is cheaper than the random I/O." }
              ]
            }
            """;

    private FileExerciseCatalog catalog(Path dir) {
        return new FileExerciseCatalog(new ContentProperties(dir.toString()), mapper);
    }

    // A linked-list exercise in the adopted LeetCode serialisation (issue #6): a list is
    // its array of values, and the cycle form { values, pos } poses "the tail joins back
    // to index pos" as ordinary case data on the argument itself.
    private static final String LIST_EXERCISE =
            """
            {
              "id": "drop-first", "title": "Drop First", "statement": "Drop the first node.",
              "domain": "algorithms", "topics": ["linked list"],
              "difficulty": "EASY", "form": "CHALLENGE",
              "response": { "kind": "code", "signature": {
                "method": "dropFirst",
                "parameters": [ { "name": "head", "type": "LIST_NODE" } ],
                "returns": "LIST_NODE" } },
              "grading": { "kind": "testCases", "comparison": "exact",
                "cases": [ { "input": [[1, 2, 3]], "expected": [2, 3] },
                           { "input": [[]], "expected": [] },
                           { "input": [null], "expected": [] } ] }
            }
            """;

    // A binary-tree exercise: LeetCode's level-order-with-nulls array, nulls being absent
    // children, [] the empty tree.
    private static final String TREE_EXERCISE =
            """
            {
              "id": "drop-right", "title": "Drop Right", "statement": "Drop the right subtree.",
              "domain": "algorithms", "topics": ["binary tree"],
              "difficulty": "EASY", "form": "CHALLENGE",
              "response": { "kind": "code", "signature": {
                "method": "dropRight",
                "parameters": [ { "name": "root", "type": "TREE_NODE" } ],
                "returns": "TREE_NODE" } },
              "grading": { "kind": "testCases", "comparison": "exact",
                "cases": [ { "input": [[3, 9, 20, null, null, 15, 7]], "expected": [3, 9] },
                           { "input": [[]], "expected": [] } ] }
            }
            """;

    // The cycle-carrying input LeetCode's linked-list-cycle problem needs: the argument
    // itself carries "pos", the index the tail joins back to.
    private static final String CYCLE_EXERCISE =
            """
            {
              "id": "revisits", "title": "Revisits", "statement": "Does it revisit a node?",
              "domain": "algorithms", "topics": ["linked list"],
              "difficulty": "EASY", "form": "CHALLENGE",
              "response": { "kind": "code", "signature": {
                "method": "revisits",
                "parameters": [ { "name": "head", "type": "LIST_NODE" } ],
                "returns": "BOOLEAN" } },
              "grading": { "kind": "testCases", "comparison": "exact",
                "cases": [ { "input": [{ "values": [3, 2, 0, -4], "pos": 1 }], "expected": true },
                           { "input": [[1, 2, 3]], "expected": false } ] }
            }
            """;

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

    // --- The competitive-distractor gate (issue #42) ----------------------------------

    @Test
    void parsesAnnotatedDistractorsAndKeepsTheMisconceptionsOffTheOptionText(@TempDir Path dir)
            throws IOException {
        write(dir, "pick.json", CHOICE_EXERCISE);

        Response.Choice choice =
                (Response.Choice) catalog(dir).byId("pick-demo").orElseThrow().response();

        // Every option's text is preserved in order...
        assertThat(choice.optionTexts()).containsExactly("A", "B");
        // ...the distractor carries its declared misconception...
        Option distractor = choice.options().get(0);
        assertThat(distractor.text()).isEqualTo("A");
        assertThat(distractor.misconception()).isEqualTo("picks the first option without reading on");
        // ...and the correct option (a plain string) declares none.
        assertThat(choice.options().get(1).misconception()).isNull();
    }

    @Test
    void aDistractorWithNoDeclaredMisconceptionFailsTheGate(@TempDir Path dir) throws IOException {
        // A deliberately bad set: the wrong option "A" is a bare string, so it names no
        // misconception - the giveaway a competitive question must not contain. It must
        // not load, and the error must name the offending option and the rule.
        String giveaway =
                """
                {
                  "id": "lazy-choice", "title": "Lazy", "statement": "Pick B.",
                  "domain": "fundamentals", "topics": ["demo"],
                  "difficulty": "EASY", "form": "REP",
                  "response": { "kind": "choice", "options": ["A", "B"] },
                  "grading": { "kind": "answerKey", "comparison": "exact", "expected": "B" }
                }
                """;
        write(dir, "lazy-choice.json", giveaway);

        assertThatThrownBy(catalog(dir)::all)
                .isInstanceOf(ContentException.class)
                .hasMessageContaining("lazy-choice.json")
                .hasMessageContaining("'A'")
                .hasMessageContaining("distractor")
                .hasMessageContaining("misconception")
                .hasMessageContaining("#42");
    }

    @Test
    void aDistractorWithABlankMisconceptionFailsTheGate(@TempDir Path dir) throws IOException {
        // "Declared but empty" must not slip through as if annotated.
        String blank =
                """
                {
                  "id": "blank-choice", "title": "Blank", "statement": "Pick B.",
                  "domain": "fundamentals", "topics": ["demo"],
                  "difficulty": "EASY", "form": "REP",
                  "response": { "kind": "choice", "options": [
                    { "text": "A", "misconception": "   " },
                    "B" ] },
                  "grading": { "kind": "answerKey", "comparison": "exact", "expected": "B" }
                }
                """;
        write(dir, "blank-choice.json", blank);

        assertThatThrownBy(catalog(dir)::all)
                .isInstanceOf(ContentException.class)
                .hasMessageContaining("blank-choice.json")
                .hasMessageContaining("misconception");
    }

    @Test
    void anAnswerKeyThatMatchesNoOptionIsAClearError(@TempDir Path dir) throws IOException {
        // A choice whose key is not one of the options has no correct answer at all.
        String unanswerable =
                """
                {
                  "id": "no-answer", "title": "No answer", "statement": "Pick the key.",
                  "domain": "fundamentals", "topics": ["demo"],
                  "difficulty": "EASY", "form": "REP",
                  "response": { "kind": "choice", "options": [
                    { "text": "A", "misconception": "an off-by-one" },
                    { "text": "B", "misconception": "a sign error" } ] },
                  "grading": { "kind": "answerKey", "comparison": "exact", "expected": "C" }
                }
                """;
        write(dir, "no-answer.json", unanswerable);

        assertThatThrownBy(catalog(dir)::all)
                .isInstanceOf(ContentException.class)
                .hasMessageContaining("no-answer.json")
                .hasMessageContaining("matches none of the choice options");
    }

    @Test
    void parsesADerivedFromGateWhenPresentAndDefaultsToNull(@TempDir Path dir) throws IOException {
        // A derived rep names the problem it is gated on (issue #18); a rep without the
        // field is available cold.
        String derived = CHOICE_EXERCISE.replaceFirst(
                "\\}\\s*$",
                """
                ,
                  "derivedFrom": "two-sum"
                }
                """);
        write(dir, "derived.json", derived);
        write(dir, "cold.json", CODE_EXERCISE);

        assertThat(catalog(dir).byId("pick-demo").orElseThrow().derivedFrom()).isEqualTo("two-sum");
        assertThat(catalog(dir).byId("echo-demo").orElseThrow().derivedFrom()).isNull();
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
        // A lesson with no prompts declared loads with an empty prompt list (issue #41).
        assertThat(loaded.prompts()).isEmpty();
    }

    @Test
    void loadsALessonsUngradedSelfExplanationPrompts(@TempDir Path dir) throws IOException {
        write(dir, "idx.json", LESSON_WITH_PROMPTS);

        Lesson loaded = (Lesson) catalog(dir).contentById("concept-indexes").orElseThrow();

        assertThat(loaded.prompts())
                .extracting(SelfExplainPrompt::prompt)
                .containsExactly(
                        "Explain why a function-wrapped column skips the index.",
                        "Predict the plan for a 90%-selectivity query.");
        assertThat(loaded.prompts().get(0).modelAnswer())
                .isEqualTo("The index stores raw values, not the function output.");
    }

    @Test
    void aMalformedLessonPromptNamesFileAndField(@TempDir Path dir) throws IOException {
        // A prompt object missing its modelAnswer fails naming the file and the field.
        String malformed = LESSON_WITH_PROMPTS.replace("\"modelAnswer\"", "\"nope\"");
        write(dir, "idx.json", malformed);

        assertThatThrownBy(catalog(dir)::allContent)
                .isInstanceOf(ContentException.class)
                .hasMessageContaining("idx.json")
                .hasMessageContaining("modelAnswer");
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

    // --- Complexity self-report metadata (issue #17) ----------------------------------

    @Test
    void parsesAComplexityCheckWithAnInputGenerator(@TempDir Path dir) throws IOException {
        String withComplexity = CODE_EXERCISE_WITH_ARRAY.replaceFirst(
                "\\}\\s*$",
                """
                ,
                  "complexity": {
                    "targetTime": "LINEAR", "targetSpace": "CONSTANT",
                    "generator": { "arguments": [
                      { "kind": "scalingIntArray", "min": 0, "max": 1000 },
                      { "kind": "fixed", "value": 5 } ] }
                  }
                }
                """);
        write(dir, "sum.json", withComplexity);

        Exercise loaded = catalog(dir).byId("sum-demo").orElseThrow();

        ComplexityCheck check = loaded.complexityCheck();
        assertThat(check).isNotNull();
        assertThat(check.targetTime()).isEqualTo(Complexity.LINEAR);
        assertThat(check.targetSpace()).isEqualTo(Complexity.CONSTANT);
        assertThat(check.generator()).isNotNull();
        assertThat(check.generator().arguments()).hasSize(2);
        assertThat(check.generator().arguments().get(0))
                .isInstanceOf(InputGenerator.Argument.ScalingIntArray.class);
        assertThat(check.generator().arguments().get(1))
                .isInstanceOfSatisfying(
                        InputGenerator.Argument.Fixed.class,
                        fixed -> assertThat(fixed.value().asInt()).isEqualTo(5));
    }

    @Test
    void anExerciseWithNoComplexityBlockLoadsWithNullComplexityCheck(@TempDir Path dir) throws IOException {
        write(dir, "echo.json", CODE_EXERCISE);

        assertThat(catalog(dir).byId("echo-demo").orElseThrow().complexityCheck()).isNull();
    }

    @Test
    void aComplexityCheckWithNoGeneratorStillLoadsAndSkipsOnlyMeasurement(@TempDir Path dir)
            throws IOException {
        // A target with no generator: the ask-and-reveal flow still applies, only the
        // empirical check is skipped (issue #17's explicit acceptance criterion).
        String targetOnly = CODE_EXERCISE_WITH_ARRAY.replaceFirst(
                "\\}\\s*$",
                """
                ,
                  "complexity": { "targetTime": "LINEAR", "targetSpace": "LINEAR" }
                }
                """);
        write(dir, "sum.json", targetOnly);

        ComplexityCheck check = catalog(dir).byId("sum-demo").orElseThrow().complexityCheck();
        assertThat(check).isNotNull();
        assertThat(check.generator()).isNull();
    }

    @Test
    void aComplexityGeneratorOnANonCodeResponseFailsTheGate(@TempDir Path dir) throws IOException {
        String badGenerator = CHOICE_EXERCISE.replaceFirst(
                "\\}\\s*$",
                """
                ,
                  "complexity": {
                    "targetTime": "LINEAR", "targetSpace": "CONSTANT",
                    "generator": { "arguments": [ { "kind": "fixed", "value": 1 } ] }
                  }
                }
                """);
        write(dir, "pick.json", badGenerator);

        assertThatThrownBy(catalog(dir)::all)
                .isInstanceOf(ContentException.class)
                .hasMessageContaining("pick.json")
                .hasMessageContaining("code response");
    }

    @Test
    void aComplexityGeneratorArgumentCountMismatchFailsTheGate(@TempDir Path dir) throws IOException {
        // Only one argument spec for a two-parameter signature.
        String mismatched = CODE_EXERCISE_WITH_ARRAY.replaceFirst(
                "\\}\\s*$",
                """
                ,
                  "complexity": {
                    "targetTime": "LINEAR", "targetSpace": "CONSTANT",
                    "generator": { "arguments": [
                      { "kind": "scalingIntArray", "min": 0, "max": 1000 } ] }
                  }
                }
                """);
        write(dir, "sum.json", mismatched);

        assertThatThrownBy(catalog(dir)::all)
                .isInstanceOf(ContentException.class)
                .hasMessageContaining("sum.json")
                .hasMessageContaining("one entry per signature parameter");
    }

    @Test
    void aScalingIntArrayOnANonArrayParameterFailsTheGate(@TempDir Path dir) throws IOException {
        // "target" is an INT, not an INT_ARRAY - declaring it as a scaling array is wrong.
        String badType = CODE_EXERCISE_WITH_ARRAY.replaceFirst(
                "\\}\\s*$",
                """
                ,
                  "complexity": {
                    "targetTime": "LINEAR", "targetSpace": "CONSTANT",
                    "generator": { "arguments": [
                      { "kind": "scalingIntArray", "min": 0, "max": 1000 },
                      { "kind": "scalingIntArray", "min": 0, "max": 1000 } ] }
                  }
                }
                """);
        write(dir, "sum.json", badType);

        assertThatThrownBy(catalog(dir)::all)
                .isInstanceOf(ContentException.class)
                .hasMessageContaining("sum.json")
                .hasMessageContaining("target")
                .hasMessageContaining("INT_ARRAY");
    }

    @Test
    void anUnknownComplexityTargetIsAClearError(@TempDir Path dir) throws IOException {
        String badTarget = CODE_EXERCISE_WITH_ARRAY.replaceFirst(
                "\\}\\s*$",
                """
                ,
                  "complexity": { "targetTime": "SLOW", "targetSpace": "CONSTANT" }
                }
                """);
        write(dir, "sum.json", badTarget);

        assertThatThrownBy(catalog(dir)::all)
                .isInstanceOf(ContentException.class)
                .hasMessageContaining("sum.json")
                .hasMessageContaining("targetTime")
                .hasMessageContaining("SLOW");
    }

    // The SQL domain (issue #25): a Query response paired with a ResultSet grading. Rows
    // are column-positional arrays and the default comparison is order-insensitive - the
    // opposite default from testCases/answerKey, since SQL row order is otherwise
    // unspecified unless the exercise's own statement asks for one.
    private static final String SQL_EXERCISE =
            """
            {
              "id": "top-customers", "title": "Top Customers",
              "statement": "Return every customer id and name.",
              "domain": "sql", "topics": ["demo"],
              "difficulty": "EASY", "form": "CHALLENGE",
              "response": { "kind": "query" },
              "grading": { "kind": "resultSet", "fixture": "ecommerce",
                "expected": [ [1, "Alice"], [2, "Bob"] ] }
            }
            """;

    @Test
    void loadsAQueryExerciseGradedByAResultSetDefaultingToOrderInsensitive(@TempDir Path dir)
            throws IOException {
        write(dir, "top-customers.json", SQL_EXERCISE);

        Exercise loaded = catalog(dir).byId("top-customers").orElseThrow();

        assertThat(loaded.response()).isInstanceOf(Response.Query.class);
        assertThat(loaded.grading()).isInstanceOfSatisfying(Grading.ResultSet.class, resultSet -> {
            assertThat(resultSet.fixture()).isEqualTo("ecommerce");
            assertThat(resultSet.expected()).hasSize(2);
            assertThat(resultSet.comparison()).isEqualTo(Comparison.orderInsensitiveSequence());
        });
        // A SQL exercise carries no complexity target and needs no special-casing to skip
        // it - the model's existing nullable ComplexityCheck already covers it (issue #25).
        assertThat(loaded.complexityCheck()).isNull();
    }

    @Test
    void aResultSetGradingCanRequireOrderExplicitly(@TempDir Path dir) throws IOException {
        String ordered = SQL_EXERCISE.replace(
                "\"grading\": { \"kind\": \"resultSet\", \"fixture\": \"ecommerce\",",
                "\"grading\": { \"kind\": \"resultSet\", \"fixture\": \"ecommerce\", "
                        + "\"comparison\": \"exact\",");
        write(dir, "top-customers.json", ordered);

        Exercise loaded = catalog(dir).byId("top-customers").orElseThrow();

        assertThat(loaded.grading()).isInstanceOfSatisfying(Grading.ResultSet.class,
                resultSet -> assertThat(resultSet.comparison()).isEqualTo(Comparison.exact()));
    }

    @Test
    void aFixtureNameThatIsNotASafeIdentifierIsAClearError(@TempDir Path dir) throws IOException {
        String badFixture = SQL_EXERCISE.replace("\"fixture\": \"ecommerce\"", "\"fixture\": \"bad; drop\"");
        write(dir, "top-customers.json", badFixture);

        assertThatThrownBy(catalog(dir)::all)
                .isInstanceOf(ContentException.class)
                .hasMessageContaining("top-customers.json")
                .hasMessageContaining("fixture");
    }

    @Test
    void anExpectedRowThatIsNotAnArrayIsAClearError(@TempDir Path dir) throws IOException {
        String badRow = SQL_EXERCISE.replace("[1, \"Alice\"], [2, \"Bob\"]", "1, [2, \"Bob\"]");
        write(dir, "top-customers.json", badRow);

        assertThatThrownBy(catalog(dir)::all)
                .isInstanceOf(ContentException.class)
                .hasMessageContaining("top-customers.json")
                .hasMessageContaining("expected");
    }

    // --- Linked lists and binary trees (issue #6's adopted LeetCode serialisation) ---

    @Test
    void alinkedListExerciseLoadsWithItsSerialisedCases(@TempDir Path dir) throws IOException {
        write(dir, "drop-first.json", LIST_EXERCISE);

        Exercise loaded = catalog(dir).byId("drop-first").orElseThrow();

        assertThat(loaded.response()).isInstanceOfSatisfying(Response.Code.class, code -> {
            assertThat(code.signature().parameters())
                    .singleElement()
                    .satisfies(p -> assertThat(p.type()).isEqualTo(DataType.LIST_NODE));
            assertThat(code.signature().returnType()).isEqualTo(DataType.LIST_NODE);
        });
        assertThat(loaded.grading()).isInstanceOfSatisfying(Grading.TestCases.class,
                testCases -> assertThat(testCases.cases()).hasSize(3));
    }

    @Test
    void abinaryTreeExerciseLoadsWithItsLevelOrderCases(@TempDir Path dir) throws IOException {
        write(dir, "drop-right.json", TREE_EXERCISE);

        Exercise loaded = catalog(dir).byId("drop-right").orElseThrow();

        assertThat(loaded.response()).isInstanceOfSatisfying(Response.Code.class,
                code -> assertThat(code.signature().returnType()).isEqualTo(DataType.TREE_NODE));
    }

    @Test
    void alistArgumentMayPoseACycleTheLeetCodeWay(@TempDir Path dir) throws IOException {
        write(dir, "revisits.json", CYCLE_EXERCISE);

        Exercise loaded = catalog(dir).byId("revisits").orElseThrow();

        // The cycle is ordinary case data on the argument, not a special serialiser mode:
        // the harness builds it, and the solver is handed the built list.
        assertThat(loaded.grading()).isInstanceOfSatisfying(Grading.TestCases.class,
                testCases -> assertThat(testCases.cases().get(0).input().get(0).get("pos").asInt())
                        .isEqualTo(1));
    }

    @Test
    void acycleIndexPastTheEndOfTheListIsAClearError(@TempDir Path dir) throws IOException {
        String badPos = LIST_EXERCISE.replace(
                "{ \"input\": [[1, 2, 3]], \"expected\": [2, 3] }",
                "{ \"input\": [{ \"values\": [1, 2, 3], \"pos\": 9 }], \"expected\": [2, 3] }");
        write(dir, "bad-pos.json", badPos);

        assertThatThrownBy(catalog(dir)::all)
                .isInstanceOf(ContentException.class)
                .hasMessageContaining("bad-pos.json")
                .hasMessageContaining("pos");
    }

    @Test
    void alistValueThatIsNotAnArrayOfIntegersIsAClearError(@TempDir Path dir) throws IOException {
        String badValues = LIST_EXERCISE.replace("\"input\": [[1, 2, 3]]", "\"input\": [[1, \"two\", 3]]");
        write(dir, "bad-values.json", badValues);

        assertThatThrownBy(catalog(dir)::all)
                .isInstanceOf(ContentException.class)
                .hasMessageContaining("bad-values.json")
                .hasMessageContaining("head");
    }

    @Test
    void atreeArrayStartingWithNullIsAClearError(@TempDir Path dir) throws IOException {
        // An absent root is [], never [null, ...] - the latter cannot mean anything, and
        // silently treating it as empty would hide an authoring mistake.
        String leadingNull = TREE_EXERCISE.replace(
                "\"input\": [[3, 9, 20, null, null, 15, 7]]", "\"input\": [[null, 9, 20]]");
        write(dir, "bad-root.json", leadingNull);

        assertThatThrownBy(catalog(dir)::all)
                .isInstanceOf(ContentException.class)
                .hasMessageContaining("bad-root.json")
                .hasMessageContaining("root");
    }

    @Test
    void acaseWhoseArgumentCountDoesNotMatchTheSignatureIsAClearError(@TempDir Path dir)
            throws IOException {
        String tooManyArgs = LIST_EXERCISE.replace(
                "\"input\": [[1, 2, 3]]", "\"input\": [[1, 2, 3], 4]");
        write(dir, "bad-arity.json", tooManyArgs);

        assertThatThrownBy(catalog(dir)::all)
                .isInstanceOf(ContentException.class)
                .hasMessageContaining("bad-arity.json")
                .hasMessageContaining("one argument per signature parameter");
    }
}
