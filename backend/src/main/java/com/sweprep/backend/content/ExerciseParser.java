package com.sweprep.backend.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.sweprep.backend.exercise.Comparison;
import com.sweprep.backend.exercise.DataType;
import com.sweprep.backend.exercise.Difficulty;
import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.Form;
import com.sweprep.backend.exercise.Grading;
import com.sweprep.backend.exercise.Response;
import com.sweprep.backend.exercise.Signature;
import com.sweprep.backend.exercise.Signature.Parameter;
import com.sweprep.backend.exercise.TestCase;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds an {@link Exercise} from the language-neutral JSON one content file
 * holds. It is hand-rolled rather than delegated to Jackson data binding so that
 * every failure names the file and the exact field at fault - "malformed content
 * reports a clear error" is an acceptance criterion (issue #14), and a generic
 * binding exception would not meet it.
 *
 * <p>The format:
 * <pre>
 * {
 *   "id": "...", "title": "...", "statement": "...",
 *   "domain": "algorithms", "topics": ["array"],
 *   "difficulty": "EASY|MEDIUM|HARD", "form": "REP|CHALLENGE",
 *   "response": { "kind": "code",   "signature": {...} }
 *             |  { "kind": "choice", "options": ["..."] },
 *   "grading":  { "kind": "testCases", "comparison": "...", "cases": [...] }
 *             |  { "kind": "answerKey", "comparison": "...", "expected": ... }
 * }
 * </pre>
 */
final class ExerciseParser {

    private ExerciseParser() {}

    /** Parse one exercise; {@code source} names the file for error messages. */
    static Exercise parse(String source, JsonNode root) {
        if (root == null || !root.isObject()) {
            throw malformed(source, "the file is not a JSON object");
        }
        String id = requireText(source, root, "id");
        String title = requireText(source, root, "title");
        String statement = requireText(source, root, "statement");
        String domain = requireText(source, root, "domain");
        List<String> topics = topics(source, root);
        Difficulty difficulty = requireEnum(source, root, "difficulty", Difficulty.class);
        Form form = requireEnum(source, root, "form", Form.class);
        Response response = response(source, requireObject(source, root, "response"));
        Grading grading = grading(source, requireObject(source, root, "grading"));
        return new Exercise(id, title, statement, domain, topics, difficulty, form, response, grading);
    }

    private static List<String> topics(String source, JsonNode root) {
        JsonNode node = root.get("topics");
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw malformed(source, "'topics' must be an array of strings");
        }
        List<String> topics = new ArrayList<>();
        for (JsonNode topic : node) {
            if (!topic.isTextual()) {
                throw malformed(source, "'topics' must contain only strings");
            }
            topics.add(topic.asText());
        }
        return topics;
    }

    private static Response response(String source, JsonNode node) {
        String kind = requireText(source, node, "kind");
        return switch (kind) {
            case "code" -> new Response.Code(signature(source, requireObject(source, node, "signature")));
            case "choice" -> new Response.Choice(stringArray(source, node, "options"));
            default -> throw malformed(source, "unknown response kind '" + kind + "'");
        };
    }

    private static Signature signature(String source, JsonNode node) {
        String method = requireText(source, node, "method");
        JsonNode params = requireField(source, node, "parameters");
        if (!params.isArray()) {
            throw malformed(source, "'signature.parameters' must be an array");
        }
        List<Parameter> parameters = new ArrayList<>();
        for (JsonNode param : params) {
            parameters.add(new Parameter(
                    requireText(source, param, "name"),
                    requireEnum(source, param, "type", DataType.class)));
        }
        return new Signature(method, parameters, requireEnum(source, node, "returns", DataType.class));
    }

    private static Grading grading(String source, JsonNode node) {
        String kind = requireText(source, node, "kind");
        return switch (kind) {
            case "testCases" -> new Grading.TestCases(comparison(source, node), cases(source, node));
            case "answerKey" -> new Grading.AnswerKey(
                    requireField(source, node, "expected"), comparison(source, node));
            default -> throw malformed(source, "unknown grading kind '" + kind + "'");
        };
    }

    private static List<TestCase> cases(String source, JsonNode node) {
        JsonNode cases = requireField(source, node, "cases");
        if (!cases.isArray() || cases.isEmpty()) {
            throw malformed(source, "'grading.cases' must be a non-empty array");
        }
        List<TestCase> result = new ArrayList<>();
        for (JsonNode testCase : cases) {
            JsonNode input = requireField(source, testCase, "input");
            if (!input.isArray()) {
                throw malformed(source, "each case's 'input' must be a JSON array of arguments");
            }
            result.add(new TestCase(input, requireField(source, testCase, "expected")));
        }
        return result;
    }

    private static Comparison comparison(String source, JsonNode node) {
        JsonNode value = node.get("comparison");
        if (value == null || value.isNull()) {
            return Comparison.exact();
        }
        if (!value.isTextual()) {
            throw malformed(source, "'comparison' must be a string");
        }
        return switch (value.asText()) {
            case "exact" -> Comparison.exact();
            case "orderInsensitiveSequence" -> Comparison.orderInsensitiveSequence();
            case "setEquality" -> Comparison.setEquality();
            default -> throw malformed(source, "unknown comparison '" + value.asText() + "'");
        };
    }

    private static List<String> stringArray(String source, JsonNode node, String field) {
        JsonNode value = requireField(source, node, field);
        if (!value.isArray() || value.isEmpty()) {
            throw malformed(source, "'" + field + "' must be a non-empty array of strings");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode element : value) {
            if (!element.isTextual()) {
                throw malformed(source, "'" + field + "' must contain only strings");
            }
            result.add(element.asText());
        }
        return result;
    }

    private static <E extends Enum<E>> E requireEnum(
            String source, JsonNode node, String field, Class<E> type) {
        String value = requireText(source, node, field);
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw malformed(source, "'" + field + "' has unknown value '" + value + "'");
        }
    }

    private static String requireText(String source, JsonNode node, String field) {
        JsonNode value = requireField(source, node, field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw malformed(source, "'" + field + "' must be a non-empty string");
        }
        return value.asText();
    }

    private static JsonNode requireObject(String source, JsonNode node, String field) {
        JsonNode value = requireField(source, node, field);
        if (!value.isObject()) {
            throw malformed(source, "'" + field + "' must be an object");
        }
        return value;
    }

    private static JsonNode requireField(String source, JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            throw malformed(source, "missing required field '" + field + "'");
        }
        return value;
    }

    private static ContentException malformed(String source, String detail) {
        return new ContentException("Malformed exercise content in " + source + ": " + detail);
    }
}
