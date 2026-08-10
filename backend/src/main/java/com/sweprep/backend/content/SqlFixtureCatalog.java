package com.sweprep.backend.content;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Reads a named SQL fixture's DDL-plus-seed script from content, the SQL-domain sibling
 * of {@link FileExerciseCatalog} (issue #25). A fixture is one shared, realistic schema
 * many exercises are graded against (decision issue #10) - {@code sql-fixtures/{name}.sql}
 * under the same content directory {@code *.json} exercises load from, so it clones with
 * the rest of the private content and needs no separate gitignore entry (issue #4/#14).
 *
 * <p>Loading is lazy and cached per fixture name, the same discipline {@code
 * FileExerciseCatalog} uses for exercises: the first request for a fixture reads its file,
 * and a successful read is held for the process lifetime. A missing file is a {@link
 * ContentException} naming the fixture and the path it looked for, exactly like a
 * malformed exercise names its file and field.
 */
@Component
public class SqlFixtureCatalog {

    private final Path fixturesDir;
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    public SqlFixtureCatalog(ContentProperties properties) {
        this.fixturesDir = Path.of(properties.path()).resolve("sql-fixtures");
    }

    /** The DDL-plus-seed SQL text for one fixture, read once and cached thereafter. */
    public String sql(String fixture) {
        String cached = cache.get(fixture);
        if (cached != null) {
            return cached;
        }
        Path file = fixturesDir.resolve(fixture + ".sql");
        if (!Files.isRegularFile(file)) {
            throw new ContentException(
                    "No SQL fixture named '" + fixture + "' at " + file.toAbsolutePath()
                            + ". Add a sql-fixtures/" + fixture + ".sql to the content clone.");
        }
        String text;
        try {
            text = Files.readString(file);
        } catch (IOException e) {
            throw new ContentException("Could not read SQL fixture file: " + file.getFileName(), e);
        }
        if (text.isBlank()) {
            throw new ContentException("SQL fixture '" + fixture + "' at " + file.getFileName()
                    + " is empty");
        }
        cache.put(fixture, text);
        return text;
    }
}
