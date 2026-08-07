package com.sweprep.backend.content;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Reads the exercise set from a local content directory - a clone of the private
 * content repo, at a gitignored path (issue #14). Every {@code *.json} file in the
 * directory is one {@link Exercise}, parsed by {@link ExerciseParser}.
 *
 * <p>Loading is lazy and cached: the first request triggers a read, and a
 * successful read is held for the process lifetime. A <em>failed</em> read is not
 * cached, so pointing the app at a not-yet-cloned directory and then cloning it
 * makes the next request succeed without a restart. Any failure - a missing
 * directory, an unreadable file, malformed JSON, a duplicate id - is reported as a
 * {@link ContentException} whose message names the cause plainly.
 */
@Component
public class FileExerciseCatalog implements ExerciseCatalog {

    private final Path contentDir;
    private final ObjectMapper mapper;
    private volatile Map<String, Exercise> byId;

    public FileExerciseCatalog(ContentProperties properties, ObjectMapper mapper) {
        this.contentDir = Path.of(properties.path());
        this.mapper = mapper;
    }

    @Override
    public List<Exercise> all() {
        return List.copyOf(loaded().values());
    }

    @Override
    public Optional<Exercise> byId(String id) {
        return Optional.ofNullable(loaded().get(id));
    }

    private Map<String, Exercise> loaded() {
        Map<String, Exercise> local = byId;
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

    private Map<String, Exercise> load() {
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
                    "No exercise *.json files found in content directory: "
                            + contentDir.toAbsolutePath());
        }

        Map<String, Exercise> exercises = new LinkedHashMap<>();
        for (Path file : files) {
            Exercise exercise = parse(file);
            Exercise clash = exercises.put(exercise.id(), exercise);
            if (clash != null) {
                throw new ContentException(
                        "Duplicate exercise id '" + exercise.id() + "' in " + file.getFileName());
            }
        }
        return exercises;
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

    private Exercise parse(Path file) {
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
                    "Malformed exercise content in " + file.getFileName() + ": not valid JSON", e);
        }
        return ExerciseParser.parse(file.getFileName().toString(), root);
    }
}
