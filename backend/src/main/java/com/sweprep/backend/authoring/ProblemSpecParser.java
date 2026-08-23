package com.sweprep.backend.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sweprep.backend.exercise.Comparison;
import com.sweprep.backend.exercise.Complexity;
import com.sweprep.backend.exercise.ComplexityCheck;
import com.sweprep.backend.exercise.DataType;
import com.sweprep.backend.exercise.Difficulty;
import com.sweprep.backend.exercise.Family;
import com.sweprep.backend.exercise.Hint;
import com.sweprep.backend.exercise.InputGenerator;
import com.sweprep.backend.exercise.Signature;
import com.sweprep.backend.exercise.Signature.Parameter;
import com.sweprep.backend.exercise.Stability;
import com.sweprep.backend.exercise.TestCase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a {@link ProblemSpec} from its on-disk JSON form. Hand-rolled like {@code
 * ExerciseParser}, for the same reason: every failure should name the file and the
 * exact field at fault rather than surface a Jackson stack trace to an author
 * mid-session. The sub-shapes ({@code signature}, {@code comparison}, {@code
 * cases}, {@code hints}, {@code complexity.generator}) are deliberately the same
 * shape {@code ExerciseParser} reads - an author who already knows the content
 * format does not learn a second one - just lifted to the top level instead of
 * nested under {@code response}/{@code grading} (see {@link ProblemSpec}'s
 * javadoc for why that also keeps this file out of {@code check-no-content.sh}'s
 * reach).
 *
 * <pre>
 * {
 *   "id": "two-sum", "title": "Two Sum", "statement": "…",
 *   "domain": "algorithms", "topics": ["array", "hash-map"],
 *   "difficulty": "EASY",
 *   "signature": { "method": "twoSum", "parameters": [...], "returns": "INT_ARRAY" },
 *   "comparison": "orderInsensitiveSequence",           // optional, default "exact"
 *   "cases": [ { "input": [[2,7,11,15], 9], "expected": [0, 1] } ],
 *   "referenceSolution": "class Solution { ... }",       // authoring-only; never shipped
 *   "hints": [ { "name": "Pattern", "body": "…" } ],      // optional
 *   "explanation": "…",                                   // optional
 *   "family": ["BACKEND"],                                // optional
 *   "stability": "STABLE",                                // optional
 *   "reviewed": "2026-08-07",                              // optional
 *   "complexity": { "targetTime": "LINEAR", "targetSpace": "CONSTANT",
 *                    "generator": { "arguments": [...] } } // optional
 * }
 * </pre>
 */
public final class ProblemSpecParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ProblemSpecParser() {}

    public static ProblemSpec parse(Path file) {
        String source = file.toString();
        JsonNode root;
        try {
            root = MAPPER.readTree(Files.readString(file));
        } catch (Exception e) {
            throw new AuthoringException("Cannot read problem spec " + source + ": " + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new AuthoringException("Malformed problem spec in " + source + ": not a JSON object");
        }
        return parse(source, root);
    }

    static ProblemSpec parse(String source, JsonNode root) {
        String id = requireText(source, root, "id");
        String title = requireText(source, root, "title");
        String statement = requireText(source, root, "statement");
        String domain = requireText(source, root, "domain");
        List<String> topics = textArray(source, root, "topics");
        Difficulty difficulty = requireEnum(source, root, "difficulty", Difficulty.class);
        Signature signature = signature(source, requireObject(source, root, "signature"));
        Comparison comparison = comparison(source, root);
        List<TestCase> cases = cases(source, root);
        String referenceSolution = requireText(source, root, "referenceSolution");
        List<Hint> hints = hints(source, root);
        String explanation = optionalText(source, root, "explanation");
        List<Family> family = familyTags(source, root);
        Stability stability = root.hasNonNull("stability")
                ? requireEnum(source, root, "stability", Stability.class)
                : null;
        LocalDate reviewed = reviewed(source, root);
        ComplexityCheck complexityCheck = complexityCheck(source, root, signature);
        return new ProblemSpec(
                id, title, statement, domain, topics, difficulty, signature, comparison, cases,
                referenceSolution, hints, explanation, family, stability, reviewed, complexityCheck);
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

    private static Comparison comparison(String source, JsonNode root) {
        JsonNode value = root.get("comparison");
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

    private static List<TestCase> cases(String source, JsonNode root) {
        JsonNode cases = requireField(source, root, "cases");
        if (!cases.isArray() || cases.isEmpty()) {
            throw malformed(source, "'cases' must be a non-empty array");
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

    private static List<Hint> hints(String source, JsonNode root) {
        JsonNode node = root.get("hints");
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw malformed(source, "'hints' must be an array of { name, body } rungs");
        }
        List<Hint> hints = new ArrayList<>();
        for (JsonNode rung : node) {
            hints.add(new Hint(requireText(source, rung, "name"), requireText(source, rung, "body")));
        }
        return hints;
    }

    private static List<Family> familyTags(String source, JsonNode root) {
        JsonNode node = root.get("family");
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw malformed(source, "'family' must be an array of role-family names");
        }
        List<Family> family = new ArrayList<>();
        for (JsonNode element : node) {
            String value = element.asText();
            try {
                family.add(Family.valueOf(value));
            } catch (IllegalArgumentException e) {
                throw malformed(source, "'family' has unknown value '" + value + "'");
            }
        }
        return family;
    }

    private static LocalDate reviewed(String source, JsonNode root) {
        JsonNode node = root.get("reviewed");
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            return LocalDate.parse(node.asText());
        } catch (DateTimeParseException e) {
            throw malformed(source, "'reviewed' must be an ISO-8601 date (YYYY-MM-DD)");
        }
    }

    private static ComplexityCheck complexityCheck(String source, JsonNode root, Signature signature) {
        JsonNode node = root.get("complexity");
        if (node == null || node.isNull()) {
            return null;
        }
        Complexity targetTime = requireEnum(source, node, "targetTime", Complexity.class);
        Complexity targetSpace = requireEnum(source, node, "targetSpace", Complexity.class);
        InputGenerator generator = inputGenerator(source, node.get("generator"), signature);
        return new ComplexityCheck(targetTime, targetSpace, generator);
    }

    private static InputGenerator inputGenerator(String source, JsonNode node, Signature signature) {
        if (node == null || node.isNull()) {
            return null;
        }
        List<Parameter> parameters = signature.parameters();
        JsonNode args = requireField(source, node, "arguments");
        if (!args.isArray() || args.size() != parameters.size()) {
            throw malformed(
                    source,
                    "'complexity.generator.arguments' must have exactly one entry per signature "
                            + "parameter (" + parameters.size() + ")");
        }
        List<InputGenerator.Argument> arguments = new ArrayList<>();
        for (int i = 0; i < parameters.size(); i++) {
            arguments.add(generatorArgument(source, args.get(i), parameters.get(i)));
        }
        return new InputGenerator(arguments);
    }

    private static InputGenerator.Argument generatorArgument(
            String source, JsonNode node, Parameter parameter) {
        String kind = requireText(source, node, "kind");
        return switch (kind) {
            case "scalingIntArray" -> {
                if (parameter.type() != DataType.INT_ARRAY) {
                    throw malformed(
                            source,
                            "'complexity.generator' declares a scalingIntArray for parameter '"
                                    + parameter.name() + "', which is not an INT_ARRAY");
                }
                yield new InputGenerator.Argument.ScalingIntArray(
                        requireField(source, node, "min").asInt(), requireField(source, node, "max").asInt());
            }
            case "scalingString" -> {
                if (parameter.type() != DataType.STRING) {
                    throw malformed(
                            source,
                            "'complexity.generator' declares a scalingString for parameter '"
                                    + parameter.name() + "', which is not a STRING");
                }
                String alphabet = optionalText(source, node, "alphabet");
                yield new InputGenerator.Argument.ScalingString(
                        alphabet == null ? InputGenerator.Argument.ScalingString.DEFAULT_ALPHABET : alphabet);
            }
            case "scalingIntMatrix" -> {
                if (parameter.type() != DataType.INT_MATRIX) {
                    throw malformed(
                            source,
                            "'complexity.generator' declares a scalingIntMatrix for parameter '"
                                    + parameter.name() + "', which is not an INT_MATRIX");
                }
                yield new InputGenerator.Argument.ScalingIntMatrix(
                        requireField(source, node, "min").asInt(), requireField(source, node, "max").asInt());
            }
            case "scalingListNode" -> {
                if (parameter.type() != DataType.LIST_NODE) {
                    throw malformed(
                            source,
                            "'complexity.generator' declares a scalingListNode for parameter '"
                                    + parameter.name() + "', which is not a LIST_NODE");
                }
                yield new InputGenerator.Argument.ScalingListNode(
                        requireField(source, node, "min").asInt(), requireField(source, node, "max").asInt());
            }
            case "scalingTreeNode" -> {
                if (parameter.type() != DataType.TREE_NODE) {
                    throw malformed(
                            source,
                            "'complexity.generator' declares a scalingTreeNode for parameter '"
                                    + parameter.name() + "', which is not a TREE_NODE");
                }
                yield new InputGenerator.Argument.ScalingTreeNode(
                        requireField(source, node, "min").asInt(), requireField(source, node, "max").asInt());
            }
            case "fixed" -> new InputGenerator.Argument.Fixed(requireField(source, node, "value"));
            default -> throw malformed(source, "unknown complexity generator argument kind '" + kind + "'");
        };
    }

    private static List<String> textArray(String source, JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw malformed(source, "'" + field + "' must be an array of strings");
        }
        List<String> values = new ArrayList<>();
        node.forEach(n -> values.add(n.asText()));
        return values;
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

    private static String optionalText(String source, JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual() || value.asText().isBlank()) {
            throw malformed(source, "'" + field + "' must be a non-empty string when present");
        }
        return value.asText();
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

    private static AuthoringException malformed(String source, String detail) {
        return new AuthoringException("Malformed problem spec in " + source + ": " + detail);
    }
}
