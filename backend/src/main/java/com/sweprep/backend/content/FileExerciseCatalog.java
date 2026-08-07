package com.sweprep.backend.content;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sweprep.backend.exercise.Content;
import com.sweprep.backend.exercise.ContentCatalog;
import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.ExerciseCatalog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Reads the content set from a local content directory - a clone of the private
 * content repo, at a gitignored path (issue #14). Every {@code *.json} file in the
 * directory is one {@link Content} item, parsed by {@link ContentParser}, which
 * reads the top-level {@code kind} discriminator (default {@code "exercise"}) and
 * builds an {@link Exercise} or a {@link com.sweprep.backend.exercise.Lesson}
 * accordingly (issue #46).
 *
 * <p>This one bean satisfies two seams. {@link ContentCatalog} is the wide view over
 * every loaded item of either kind; {@link ExerciseCatalog} is the narrow view onto
 * just the exercises, for the consumers that attempt and grade - a lesson is read,
 * never attempted, so it never reaches them.
 *
 * <p>Loading is lazy and cached: the first request triggers a read, and a
 * successful read is held for the process lifetime. A <em>failed</em> read is not
 * cached, so pointing the app at a not-yet-cloned directory and then cloning it
 * makes the next request succeed without a restart. Any failure - a missing
 * directory, an unreadable file, malformed JSON, a duplicate id - is reported as a
 * {@link ContentException} whose message names the cause plainly.
 */
@Component
public class FileExerciseCatalog implements ContentCatalog, ExerciseCatalog {

    private final Path contentDir;
    private final ObjectMapper mapper;
    private volatile Map<String, Content> byId;

    public FileExerciseCatalog(ContentProperties properties, ObjectMapper mapper) {
        this.contentDir = Path.of(properties.path());
        this.mapper = mapper;
    }

    @Override
    public List<Content> allContent() {
        return List.copyOf(loaded().values());
    }

    @Override
    public Optional<Content> contentById(String id) {
        return Optional.ofNullable(loaded().get(id));
    }

    @Override
    public List<Exercise> all() {
        return loaded().values().stream()
                .filter(Exercise.class::isInstance)
                .map(Exercise.class::cast)
                .toList();
    }

    @Override
    public Optional<Exercise> byId(String id) {
        return contentById(id)
                .filter(Exercise.class::isInstance)
                .map(Exercise.class::cast);
    }

    private Map<String, Content> loaded() {
        Map<String, Content> local = byId;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (byId == null) {
                byId = load();
            }
            return byId;
        }
    }

    private Map<String, Content> load() {
        if (!Files.exists(contentDir)) {
            throw new ContentException(
                    "Content directory not found: " + contentDir.toAbsolutePath()
                            + ". Clone the private swe-prep-content repo there, or set"
                            + " sweprep.content.path to your local clone.");
        }
        if (!Files.isDirectory(contentDir)) {
            throw new ContentException(
                    "Content path is not a directory: " + contentDir.toAbsolutePath());
        }

        List<Path> files = jsonFiles();
        if (files.isEmpty()) {
            throw new ContentException(
                    "No content *.json files found in content directory: "
                            + contentDir.toAbsolutePath());
        }

        Map<String, Content> content = new LinkedHashMap<>();
        for (Path file : files) {
            Content item = parse(file);
            Content clash = content.put(item.id(), item);
            if (clash != null) {
                throw new ContentException(
                        "Duplicate content id '" + item.id() + "' in " + file.getFileName());
            }
        }
        return content;
    }

    private List<Path> jsonFiles() {
        try (Stream<Path> entries = Files.list(contentDir)) {
            return entries
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new ContentException(
                    "Could not read content directory: " + contentDir.toAbsolutePath(), e);
        }
    }

    private Content parse(Path file) {
        String text;
        try {
            text = Files.readString(file);
        } catch (IOException e) {
            throw new ContentException("Could not read content file: " + file.getFileName(), e);
        }
        JsonNode root;
        try {
            root = mapper.readTree(text);
        } catch (JsonProcessingException e) {
            throw new ContentException(
                    "Malformed content in " + file.getFileName() + ": not valid JSON", e);
        }
        return ContentParser.parse(file.getFileName().toString(), root);
    }
}
