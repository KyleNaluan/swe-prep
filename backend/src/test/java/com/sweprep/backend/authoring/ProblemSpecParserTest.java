package com.sweprep.backend.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sweprep.backend.exercise.Comparison;
import com.sweprep.backend.exercise.DataType;
import com.sweprep.backend.exercise.Difficulty;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves the problem-spec format parses into a {@link ProblemSpec} correctly and
 * that a malformed spec fails naming the field, matching {@code ExerciseParser}'s
 * own convention. Every fixture here is synthetic, non-real content (a two-int
 * "add" problem) - never a real interview problem (issue #4/#14).
 */
class ProblemSpecParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static final String VALID =
            """
            {
              "id": "add-two",
              "title": "Add Two",
              "statement": "Return a + b.",
              "domain": "algorithms",
              "topics": ["array"],
              "difficulty": "EASY",
              "signature": {
                "method": "addTwo",
                "parameters": [
                  { "name": "a", "type": "INT" },
                  { "name": "b", "type": "INT" }
                ],
                "returns": "INT"
              },
              "comparison": "exact",
              "cases": [ { "input": [1, 2], "expected": 3 } ],
              "referenceSolution": "class Solution { public int addTwo(int a, int b) { return a + b; } }",
              "explanation": "Sum the two arguments."
            }
            """;

    @Test
    void parsesEveryFieldOfAValidSpec(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("add-two.json");
        Files.writeString(file, VALID);

        ProblemSpec spec = ProblemSpecParser.parse(file);

        assertThat(spec.id()).isEqualTo("add-two");
        assertThat(spec.title()).isEqualTo("Add Two");
        assertThat(spec.domain()).isEqualTo("algorithms");
        assertThat(spec.topics()).containsExactly("array");
        assertThat(spec.difficulty()).isEqualTo(Difficulty.EASY);
        assertThat(spec.signature().methodName()).isEqualTo("addTwo");
        assertThat(spec.signature().parameters()).hasSize(2);
        assertThat(spec.signature().returnType()).isEqualTo(DataType.INT);
        assertThat(spec.comparison()).isInstanceOf(Comparison.Exact.class);
        assertThat(spec.cases()).hasSize(1);
        assertThat(spec.referenceSolution()).contains("a + b");
        assertThat(spec.explanation()).isEqualTo("Sum the two arguments.");
    }

    @Test
    void missingReferenceSolutionFailsNamingTheField(@TempDir Path dir) throws Exception {
        JsonNode root = mapper.readTree(VALID).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) root).remove("referenceSolution");
        Path file = dir.resolve("bad.json");
        Files.writeString(file, root.toString());

        assertThatThrownBy(() -> ProblemSpecParser.parse(file))
                .isInstanceOf(AuthoringException.class)
                .hasMessageContaining("referenceSolution");
    }

    @Test
    void missingCasesFailsNamingTheField(@TempDir Path dir) throws Exception {
        JsonNode root = mapper.readTree(VALID).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) root).remove("cases");
        Path file = dir.resolve("bad.json");
        Files.writeString(file, root.toString());

        assertThatThrownBy(() -> ProblemSpecParser.parse(file))
                .isInstanceOf(AuthoringException.class)
                .hasMessageContaining("cases");
    }

    @Test
    void unknownComparisonFailsNamingTheField(@TempDir Path dir) throws Exception {
        JsonNode root = mapper.readTree(VALID).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) root).put("comparison", "reversed");
        Path file = dir.resolve("bad.json");
        Files.writeString(file, root.toString());

        assertThatThrownBy(() -> ProblemSpecParser.parse(file))
                .isInstanceOf(AuthoringException.class)
                .hasMessageContaining("comparison");
    }

    @Test
    void unreadableFileFailsClearly(@TempDir Path dir) {
        Path missing = dir.resolve("does-not-exist.json");

        assertThatThrownBy(() -> ProblemSpecParser.parse(missing))
                .isInstanceOf(AuthoringException.class)
                .hasMessageContaining("does-not-exist.json");
    }
}
