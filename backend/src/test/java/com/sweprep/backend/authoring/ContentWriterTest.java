package com.sweprep.backend.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sweprep.backend.content.ContentProperties;
import com.sweprep.backend.content.FileExerciseCatalog;
import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.ExerciseCatalog;
import com.sweprep.backend.testsupport.Fixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves {@link ContentWriter} emits exactly the JSON shape the real loader
 * reads, both by inspecting the written JSON directly and by loading it back
 * through the production {@code FileExerciseCatalog}/{@code ExerciseParser} -
 * the two are never allowed to drift apart silently.
 */
class ContentWriterTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ContentWriter writer = new ContentWriter(mapper);

    @Test
    void writesACodeExerciseInTheDocumentedShape(@TempDir Path dir) throws Exception {
        writer.writeExercise(Fixtures.pairInAnyOrder(), dir);

        JsonNode root = mapper.readTree(dir.resolve("pair-in-any-order.json").toFile());

        assertThat(root.get("id").asText()).isEqualTo("pair-in-any-order");
        assertThat(root.get("response").get("kind").asText()).isEqualTo("code");
        assertThat(root.get("response").get("signature").get("method").asText()).isEqualTo("pair");
        assertThat(root.get("grading").get("kind").asText()).isEqualTo("testCases");
        assertThat(root.get("grading").get("comparison").asText()).isEqualTo("orderInsensitiveSequence");
        assertThat(root.get("grading").get("cases")).hasSize(3);
    }

    @Test
    void writesAChoiceExerciseWithMisconceptionsPreserved(@TempDir Path dir) throws Exception {
        writer.writeExercise(Fixtures.concept(), dir);

        JsonNode root = mapper.readTree(dir.resolve("concept-demo.json").toFile());

        assertThat(root.get("response").get("kind").asText()).isEqualTo("choice");
        JsonNode options = root.get("response").get("options");
        assertThat(options).hasSize(3);
        // The correct option ("B") needs no misconception and may serialise as a plain string.
        assertThat(options.get(1).isTextual()).isTrue();
        assertThat(options.get(1).asText()).isEqualTo("B");
        // Every distractor must round-trip as the object form carrying its misconception.
        assertThat(options.get(0).get("misconception").asText()).isNotBlank();
        assertThat(root.get("grading").get("kind").asText()).isEqualTo("answerKey");
        assertThat(root.get("grading").get("expected").asText()).isEqualTo("B");
    }

    @Test
    void writesASolutionFileUnderSolutionsSubdirectory(@TempDir Path dir) throws Exception {
        writer.writeSolution("two-sum", "class Solution {}", dir);

        Path solution = dir.resolve("solutions").resolve("two-sum.java");
        assertThat(Files.exists(solution)).isTrue();
        assertThat(Files.readString(solution)).isEqualTo("class Solution {}");
    }

    // --- LeetCode-style examples and constraints (issue: swe-examples-feedback) -------

    @Test
    void writesExamplesAndConstraintsAndLoadsThemBackUnchanged(@TempDir Path dir) throws Exception {
        Exercise base = Fixtures.pairInAnyOrder();
        Exercise withExamples = new Exercise(
                base.id(), base.title(), base.statement(), base.domain(), base.topics(),
                base.difficulty(), base.form(), base.response(), base.grading(), base.hints(),
                base.explanation(), base.family(), base.stability(), base.reviewed(),
                base.derivedFrom(), base.complexityCheck(),
                java.util.List.of(new com.sweprep.backend.exercise.Example(
                        "nums = [2,7,11,15], target = 9", "[0,1]", "explains it")),
                java.util.List.of("2 <= nums.length <= 10^4"));

        writer.writeExercise(withExamples, dir);

        JsonNode root = mapper.readTree(dir.resolve(base.id() + ".json").toFile());
        assertThat(root.get("examples")).hasSize(1);
        assertThat(root.get("examples").get(0).get("input").asText())
                .isEqualTo("nums = [2,7,11,15], target = 9");
        assertThat(root.get("constraints").get(0).asText()).isEqualTo("2 <= nums.length <= 10^4");

        ExerciseCatalog catalog = new FileExerciseCatalog(new ContentProperties(dir.toString()), mapper);
        Exercise reloaded = catalog.byId(base.id()).orElseThrow();
        assertThat(reloaded.examples()).hasSize(1);
        assertThat(reloaded.examples().get(0).output()).isEqualTo("[0,1]");
        assertThat(reloaded.constraints()).containsExactly("2 <= nums.length <= 10^4");
    }

    @Test
    void anExerciseWithNoExamplesOrConstraintsWritesNeitherField(@TempDir Path dir) throws Exception {
        writer.writeExercise(Fixtures.pairInAnyOrder(), dir);

        JsonNode root = mapper.readTree(dir.resolve("pair-in-any-order.json").toFile());
        assertThat(root.has("examples")).isFalse();
        assertThat(root.has("constraints")).isFalse();
    }

    @Test
    void everyWrittenExerciseLoadsBackThroughTheRealProductionLoader(@TempDir Path dir) throws Exception {
        writer.writeExercise(Fixtures.pairInAnyOrder(), dir);
        writer.writeExercise(Fixtures.concept(), dir);
        writer.writeExercise(Fixtures.patternIdRep(), dir);
        writer.writeExercise(Fixtures.spotBugRep(), dir);
        writer.writeExercise(Fixtures.predictOutputRep(), dir);

        ExerciseCatalog catalog = new FileExerciseCatalog(new ContentProperties(dir.toString()), mapper);

        assertThat(catalog.all()).hasSize(5);
        Exercise reloadedSpotBug = catalog.byId("rep-spot-bug").orElseThrow();
        assertThat(reloadedSpotBug.derivedFrom()).isEqualTo("max-element");
    }
}
