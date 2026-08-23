package com.sweprep.backend.content;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Reads a Code exercise's authored reference solution from the private content clone
 * (issue #82) - the same {@code solutions/<id>.java} file the content-authoring tool
 * writes ({@code authoring.ContentWriter#writeSolution}), never a second copy. This is
 * a plain read-through, never a cache: the reveal policy is on-request only, so nothing
 * here loads solution text ahead of an explicit reveal call, and a solution text never
 * lands anywhere but a direct answer to one.
 *
 * <p>{@link #forExercise} returns empty when the file does not exist - not every
 * exercise is code-response, and an author may not have supplied one yet - rather than
 * treating an absent solution as an error; the caller decides what that means for its
 * own flow (see {@code AttemptService#revealSolution}).
 */
@Component
public class ReferenceSolutionCatalog {

    private final Path contentDir;

    public ReferenceSolutionCatalog(ContentProperties properties) {
        this.contentDir = Path.of(properties.path());
    }

    /** The reference solution source for {@code exerciseId}, or empty if none is authored. */
    public Optional<String> forExercise(String exerciseId) {
        Path file = contentDir.resolve("solutions").resolve(exerciseId + ".java");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ContentException("Could not read reference solution for '" + exerciseId + "'", e);
        }
    }
}
