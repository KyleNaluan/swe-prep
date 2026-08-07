package com.sweprep.backend.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.sweprep.backend.exercise.Content;

/**
 * The loader's single entry point for one content file (issue #46). It reads the
 * top-level {@code "kind"} discriminator - defaulting to {@code "exercise"} so every
 * file written before lessons existed loads unchanged - and dispatches to the
 * matching parser. An unknown or malformed {@code kind} fails naming the file and
 * the field, exactly as any other malformed content does.
 */
final class ContentParser {

    private ContentParser() {}

    /** Parse one content item of either kind; {@code source} names the file. */
    static Content parse(String source, JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new ContentException(
                    "Malformed content in " + source + ": the file is not a JSON object");
        }
        String kind = kind(source, root);
        return switch (kind) {
            case "exercise" -> ExerciseParser.parse(source, root);
            case "lesson" -> LessonParser.parse(source, root);
            default -> throw new ContentException(
                    "Malformed content in " + source + ": unknown 'kind' '" + kind + "'");
        };
    }

    private static String kind(String source, JsonNode root) {
        JsonNode node = root.get("kind");
        if (node == null || node.isNull()) {
            return "exercise";
        }
        if (!node.isTextual() || node.asText().isBlank()) {
            throw new ContentException(
                    "Malformed content in " + source + ": 'kind' must be a non-empty string");
        }
        return node.asText();
    }
}
