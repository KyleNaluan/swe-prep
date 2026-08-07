package com.sweprep.backend.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.sweprep.backend.exercise.Family;
import com.sweprep.backend.exercise.Stability;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * The field-level JSON reads shared by {@link ExerciseParser} and {@link
 * LessonParser}: the required scalars, the enum lookups, and the content-level
 * metadata (topics, {@code family}, {@code stability}, {@code reviewed}) both
 * content kinds carry. Hand-rolled rather than delegated to Jackson data binding so
 * that every failure names the file and the exact field at fault - the acceptance
 * criterion a malformed lesson meets exactly as a malformed exercise does (issues
 * #14, #46).
 *
 * <p>An instance is bound to one {@code source} (the file name, for messages) and
 * one {@code kind} label ({@code "exercise"} or {@code "lesson"}), so a failure
 * reads "Malformed lesson content in ..." or "Malformed exercise content in ...".
 */
final class ContentJson {

    private final String source;
    private final String kind;

    ContentJson(String source, String kind) {
        this.source = source;
        this.kind = kind;
    }

    List<String> topics(JsonNode root) {
        JsonNode node = root.get("topics");
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw malformed("'topics' must be an array of strings");
        }
        List<String> topics = new ArrayList<>();
        for (JsonNode topic : node) {
            if (!topic.isTextual()) {
                throw malformed("'topics' must contain only strings");
            }
            topics.add(topic.asText());
        }
        return topics;
    }

    List<Family> family(JsonNode root) {
        JsonNode node = root.get("family");
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw malformed("'family' must be an array of role-family names");
        }
        List<Family> family = new ArrayList<>();
        for (JsonNode element : node) {
            if (!element.isTextual()) {
                throw malformed("'family' must contain only strings");
            }
            String value = element.asText();
            try {
                family.add(Family.valueOf(value));
            } catch (IllegalArgumentException e) {
                throw malformed("'family' has unknown value '" + value + "'");
            }
        }
        return family;
    }

    Stability stability(JsonNode root) {
        JsonNode node = root.get("stability");
        if (node == null || node.isNull()) {
            return Stability.STABLE;
        }
        return requireEnum(root, "stability", Stability.class);
    }

    LocalDate reviewed(JsonNode root) {
        JsonNode node = root.get("reviewed");
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw malformed("'reviewed' must be an ISO-8601 date string (YYYY-MM-DD)");
        }
        try {
            return LocalDate.parse(node.asText());
        } catch (DateTimeParseException e) {
            throw malformed("'reviewed' must be an ISO-8601 date (YYYY-MM-DD): '" + node.asText() + "'");
        }
    }

    <E extends Enum<E>> E requireEnum(JsonNode node, String field, Class<E> type) {
        String value = requireText(node, field);
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw malformed("'" + field + "' has unknown value '" + value + "'");
        }
    }

    /**
     * Reads an optional text field: {@code null} when the field is absent or JSON
     * {@code null}, its trimmed value otherwise, and a malformed error when it is
     * present but not a non-blank string. Unlike {@link #requireText}, a missing field
     * is not an error - the caller's field is optional (e.g. a check's {@code
     * explanation}, issue #51).
     */
    String optionalText(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual() || value.asText().isBlank()) {
            throw malformed("'" + field + "' must be a non-empty string when present");
        }
        return value.asText();
    }

    String requireText(JsonNode node, String field) {
        JsonNode value = requireField(node, field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw malformed("'" + field + "' must be a non-empty string");
        }
        return value.asText();
    }

    JsonNode requireObject(JsonNode node, String field) {
        JsonNode value = requireField(node, field);
        if (!value.isObject()) {
            throw malformed("'" + field + "' must be an object");
        }
        return value;
    }

    JsonNode requireField(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            throw malformed("missing required field '" + field + "'");
        }
        return value;
    }

    ContentException malformed(String detail) {
        return new ContentException("Malformed " + kind + " content in " + source + ": " + detail);
    }
}
