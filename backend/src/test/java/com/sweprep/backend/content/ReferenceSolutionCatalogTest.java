package com.sweprep.backend.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The reference-solution reader (issue #82): reads {@code solutions/<id>.java} from the
 * content clone, the same file the content-authoring tool writes ({@code
 * authoring.ContentWriter#writeSolution}). A plain unit test, like {@code
 * SolutionCommitServiceTest} - instantiated directly against a disposable {@code @TempDir},
 * never a real clone.
 */
class ReferenceSolutionCatalogTest {

    @TempDir
    Path contentDir;

    @Test
    void returnsTheSolutionTextWhenTheFileExists() throws IOException {
        Path solutionsDir = contentDir.resolve("solutions");
        Files.createDirectories(solutionsDir);
        Files.writeString(
                solutionsDir.resolve("two-sum.java"), "class Solution {}", StandardCharsets.UTF_8);

        ReferenceSolutionCatalog catalog = new ReferenceSolutionCatalog(new ContentProperties(contentDir.toString()));

        assertThat(catalog.forExercise("two-sum")).contains("class Solution {}");
    }

    @Test
    void returnsEmptyRatherThanThrowingWhenNoSolutionIsAuthored() {
        ReferenceSolutionCatalog catalog = new ReferenceSolutionCatalog(new ContentProperties(contentDir.toString()));

        assertThat(catalog.forExercise("no-such-problem")).isEmpty();
    }

    @Test
    void returnsEmptyWhenTheSolutionsDirectoryDoesNotExistAtAll() {
        ReferenceSolutionCatalog catalog = new ReferenceSolutionCatalog(
                new ContentProperties(contentDir.resolve("no-such-content-dir").toString()));

        assertThat(catalog.forExercise("two-sum")).isEmpty();
    }

    @Test
    void aDirectoryWhereAFileIsExpectedIsNotMistakenForASolution() throws IOException {
        // solutions/two-sum.java as a directory, not a file - isRegularFile guards this.
        Path solutionsDir = contentDir.resolve("solutions");
        Files.createDirectories(solutionsDir.resolve("two-sum.java"));

        ReferenceSolutionCatalog catalog = new ReferenceSolutionCatalog(new ContentProperties(contentDir.toString()));

        assertThat(catalog.forExercise("two-sum")).isEmpty();
    }
}
