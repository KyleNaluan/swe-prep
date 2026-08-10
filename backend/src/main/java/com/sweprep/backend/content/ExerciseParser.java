package com.sweprep.backend.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sweprep.backend.exercise.Comparison;
import com.sweprep.backend.exercise.Complexity;
import com.sweprep.backend.exercise.ComplexityCheck;
import com.sweprep.backend.exercise.DataType;
import com.sweprep.backend.exercise.Difficulty;
import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.Form;
import com.sweprep.backend.exercise.Grading;
import com.sweprep.backend.exercise.Hint;
import com.sweprep.backend.exercise.InputGenerator;
import com.sweprep.backend.exercise.Option;
import com.sweprep.backend.exercise.Response;
import com.sweprep.backend.exercise.Signature;
import com.sweprep.backend.exercise.Signature.Parameter;
import com.sweprep.backend.exercise.TestCase;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Builds an {@link Exercise} from the language-neutral JSON one content file holds
 * when its {@code kind} is {@code "exercise"} (the default). The shared metadata
 * reads live in {@link ContentJson}; this parser adds the exercise-only parts - the
 * {@link Response} and {@link Grading} specs, the signature, the hint ladder and the
 * check's {@code explanation} (issue #51) - and, like {@code ContentJson}, is
 * hand-rolled so every failure names the file and the exact field at fault (issue #14).
 *
 * <p>The format:
 * <pre>
 * {
 *   "kind": "exercise",   // optional; the default (see ContentParser)
 *   "id": "...", "title": "...", "statement": "...",
 *   "domain": "algorithms", "topics": ["array"],
 *   "difficulty": "EASY|MEDIUM|HARD", "form": "REP|CHALLENGE",
 *   "response": { "kind": "code",   "signature": {...} }
 *             |  { "kind": "choice", "options": [                     // issue #42
 *                    "The correct answer",                            // a plain string, or
 *                    { "text": "A wrong option",                      // an object whose
 *                      "misconception": "the specific mistake it catches" } ] }
 *             |  { "kind": "freeText" }
 *             |  { "kind": "query" },                                  // issue #25, a SQL query
 *   "grading":  { "kind": "testCases", "comparison": "...", "cases": [...] }
 *             |  { "kind": "answerKey", "comparison": "...", "expected": ... }
 *             |  { "kind": "selfCheck", "modelAnswer": "..." }
 *             |  { "kind": "resultSet",                                // issue #25
 *                    "fixture": "ecommerce",                           // a sql-fixtures/*.sql name
 *                    "comparison": "orderInsensitiveSequence",         // optional; default here
 *                                                                      // (unlike testCases/
 *                                                                      // answerKey) since SQL
 *                                                                      // row order is otherwise
 *                                                                      // unspecified
 *                    "expected": [ [1, "Alice"], [2, "Bob"] ] },       // rows, columns by position
 *   "hints":    [ { "name": "Pattern", "body": "..." }, ... ], // optional ladder
 *   "explanation": "why the correct answer is correct",        // optional (issue #51)
 *   "family":   ["BACKEND", "AIML"],                           // optional, default []
 *   "stability": "STABLE|VOLATILE",                            // optional, default STABLE
 *   "reviewed": "2026-08-07",                                  // optional ISO date (VOLATILE)
 *   "derivedFrom": "two-sum",                                  // optional; the problem
 *                                                              // a rep is gated on (issue #18)
 *   "complexity": {                                            // optional (issue #17)
 *     "targetTime": "LINEAR", "targetSpace": "CONSTANT",
 *     "generator": {                                           // optional; omit to still
 *       "arguments": [                                         // ask + reveal but skip
 *          { "kind": "scalingIntArray", "min": 0, "max": 1000 },// the empirical check
 *          { "kind": "fixed", "value": 9 } ]                   // one entry per signature
 *     }                                                        // parameter, in order
 *   }
 * }
 * </pre>
 */
final class ExerciseParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ExerciseParser() {}

    /** Parse one exercise; {@code source} names the file for error messages. */
    static Exercise parse(String source, JsonNode root) {
        ContentJson json = new ContentJson(source, "exercise");
        String id = json.requireText(root, "id");
        String title = json.requireText(root, "title");
        String statement = json.requireText(root, "statement");
        String domain = json.requireText(root, "domain");
        List<String> topics = json.topics(root);
        Difficulty difficulty = json.requireEnum(root, "difficulty", Difficulty.class);
        Form form = json.requireEnum(root, "form", Form.class);
        Response response = response(json, json.requireObject(root, "response"));
        Grading grading = grading(json, json.requireObject(root, "grading"));
        validateDistractors(json, response, grading);
        List<Hint> hints = hints(json, root);
        String explanation = json.optionalText(root, "explanation");
        String derivedFrom = json.optionalText(root, "derivedFrom");
        ComplexityCheck complexityCheck = complexityCheck(json, root, response);
        return new Exercise(
                id, title, statement, domain, topics, difficulty, form, response, grading, hints,
                explanation, json.family(root), json.stability(root), json.reviewed(root),
                derivedFrom, complexityCheck);
    }

    /**
     * The complexity self-report flow's optional content metadata (issue #17): the
     * authored target, and, optionally, how to synthesize growing-size inputs to check a
     * claim against it. Absent entirely by default - the field is optional by design, and
     * a missing {@code "complexity"} block skips the whole flow for this exercise, not
     * just its measurement.
     */
    private static ComplexityCheck complexityCheck(ContentJson json, JsonNode root, Response response) {
        JsonNode node = root.get("complexity");
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw json.malformed("'complexity' must be an object");
        }
        Complexity targetTime = json.requireEnum(node, "targetTime", Complexity.class);
        Complexity targetSpace = json.requireEnum(node, "targetSpace", Complexity.class);
        InputGenerator generator = inputGenerator(json, node.get("generator"), response);
        return new ComplexityCheck(targetTime, targetSpace, generator);
    }

    /**
     * The optional input generator inside a {@code "complexity"} block. Absent by design
     * (issue #17's "skips the check entirely without error" criterion) - an exercise may
     * declare a target with no generator, and this returns {@code null} for exactly that
     * case. When present, it requires a code response (there is nothing to run growing
     * inputs against otherwise) and exactly one argument spec per signature parameter, in
     * order - the same "one entry per parameter" discipline a hand-authored
     * {@code TestCase.input} follows.
     */
    private static InputGenerator inputGenerator(ContentJson json, JsonNode node, Response response) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!(response instanceof Response.Code code)) {
            throw json.malformed(
                    "'complexity.generator' requires a code response to run growing inputs against");
        }
        List<Parameter> parameters = code.signature().parameters();
        JsonNode args = json.requireField(node, "arguments");
        if (!args.isArray() || args.size() != parameters.size()) {
            throw json.malformed(
                    "'complexity.generator.arguments' must have exactly one entry per signature "
                            + "parameter (" + parameters.size() + ")");
        }
        List<InputGenerator.Argument> arguments = new ArrayList<>();
        for (int i = 0; i < parameters.size(); i++) {
            arguments.add(generatorArgument(json, args.get(i), parameters.get(i)));
        }
        return new InputGenerator(arguments);
    }

    private static InputGenerator.Argument generatorArgument(
            ContentJson json, JsonNode node, Parameter parameter) {
        String kind = json.requireText(node, "kind");
        return switch (kind) {
            case "scalingIntArray" -> {
                if (parameter.type() != DataType.INT_ARRAY) {
                    throw json.malformed(
                            "'complexity.generator' declares a scalingIntArray for parameter '"
                                    + parameter.name() + "', which is not an INT_ARRAY");
                }
                yield new InputGenerator.Argument.ScalingIntArray(
                        json.requireField(node, "min").asInt(), json.requireField(node, "max").asInt());
            }
            case "fixed" -> new InputGenerator.Argument.Fixed(json.requireField(node, "value"));
            default -> throw json.malformed(
                    "unknown complexity generator argument kind '" + kind + "'");
        };
    }

    private static List<Hint> hints(ContentJson json, JsonNode root) {
        JsonNode node = root.get("hints");
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw json.malformed("'hints' must be an array of { name, body } rungs");
        }
        List<Hint> hints = new ArrayList<>();
        for (JsonNode rung : node) {
            if (!rung.isObject()) {
                throw json.malformed("each hint must be an object with 'name' and 'body'");
            }
            hints.add(new Hint(json.requireText(rung, "name"), json.requireText(rung, "body")));
        }
        return hints;
    }

    private static Response response(ContentJson json, JsonNode node) {
        String kind = json.requireText(node, "kind");
        return switch (kind) {
            case "code" -> new Response.Code(signature(json, json.requireObject(node, "signature")));
            case "choice" -> new Response.Choice(options(json, node));
            case "freeText" -> new Response.FreeText();
            case "query" -> new Response.Query();
            default -> throw json.malformed("unknown response kind '" + kind + "'");
        };
    }

    private static Signature signature(ContentJson json, JsonNode node) {
        String method = json.requireText(node, "method");
        JsonNode params = json.requireField(node, "parameters");
        if (!params.isArray()) {
            throw json.malformed("'signature.parameters' must be an array");
        }
        List<Parameter> parameters = new ArrayList<>();
        for (JsonNode param : params) {
            parameters.add(new Parameter(
                    json.requireText(param, "name"),
                    json.requireEnum(param, "type", DataType.class)));
        }
        return new Signature(method, parameters, json.requireEnum(node, "returns", DataType.class));
    }

    private static Grading grading(ContentJson json, JsonNode node) {
        String kind = json.requireText(node, "kind");
        return switch (kind) {
            case "testCases" -> new Grading.TestCases(comparison(json, node), cases(json, node));
            case "answerKey" -> new Grading.AnswerKey(
                    json.requireField(node, "expected"), comparison(json, node));
            case "selfCheck" -> new Grading.SelfCheck(json.requireText(node, "modelAnswer"));
            case "resultSet" -> new Grading.ResultSet(
                    fixtureName(json, node), expectedRows(json, node),
                    // Unlike testCases/answerKey, the field's absence means "row order does
                    // not matter" (issue #25) - Grading.ResultSet's own compact constructor
                    // supplies that default, so only an explicit value is read here.
                    node.hasNonNull("comparison") ? comparison(json, node) : null);
            default -> throw json.malformed("unknown grading kind '" + kind + "'");
        };
    }

    // A fixture name becomes a Postgres schema and role identifier (issue #25), built by
    // string concatenation from content-authored text rather than a bind parameter - safe
    // only because content is a trusted, authored source, and only once this shape is
    // enforced at load time so a typo or bad file can never smuggle SQL into an identifier
    // position.
    private static final Pattern FIXTURE_NAME = Pattern.compile("[a-z][a-z0-9_]*");

    private static String fixtureName(ContentJson json, JsonNode node) {
        String fixture = json.requireText(node, "fixture");
        if (!FIXTURE_NAME.matcher(fixture).matches()) {
            throw json.malformed(
                    "'grading.fixture' must be a lowercase identifier (letters, digits, "
                            + "underscore, starting with a letter): '" + fixture + "'");
        }
        return fixture;
    }

    private static JsonNode expectedRows(ContentJson json, JsonNode node) {
        JsonNode expected = json.requireField(node, "expected");
        if (!expected.isArray()) {
            throw json.malformed("'grading.expected' must be a JSON array of rows");
        }
        for (JsonNode row : expected) {
            if (!row.isArray()) {
                throw json.malformed(
                        "each row in 'grading.expected' must itself be a JSON array of column "
                                + "values, matched by position - column names are never compared");
            }
        }
        return expected;
    }

    private static List<TestCase> cases(ContentJson json, JsonNode node) {
        JsonNode cases = json.requireField(node, "cases");
        if (!cases.isArray() || cases.isEmpty()) {
            throw json.malformed("'grading.cases' must be a non-empty array");
        }
        List<TestCase> result = new ArrayList<>();
        for (JsonNode testCase : cases) {
            JsonNode input = json.requireField(testCase, "input");
            if (!input.isArray()) {
                throw json.malformed("each case's 'input' must be a JSON array of arguments");
            }
            result.add(new TestCase(input, json.requireField(testCase, "expected")));
        }
        return result;
    }

    private static Comparison comparison(ContentJson json, JsonNode node) {
        JsonNode value = node.get("comparison");
        if (value == null || value.isNull()) {
            return Comparison.exact();
        }
        if (!value.isTextual()) {
            throw json.malformed("'comparison' must be a string");
        }
        return switch (value.asText()) {
            case "exact" -> Comparison.exact();
            case "orderInsensitiveSequence" -> Comparison.orderInsensitiveSequence();
            case "setEquality" -> Comparison.setEquality();
            default -> throw json.malformed("unknown comparison '" + value.asText() + "'");
        };
    }

    /**
     * The choice options. Each element is either a plain string (an option that declares
     * no misconception) or an object {@code { "text": ..., "misconception": ... }}. The
     * shape is lenient on purpose: the semantic bar - every distractor must name a
     * misconception - is enforced in {@link #validateDistractors} once the answer key
     * (and thus which option is correct) is known, so it can name the exact offending
     * option rather than every bare string.
     */
    private static List<Option> options(ContentJson json, JsonNode node) {
        JsonNode value = json.requireField(node, "options");
        if (!value.isArray() || value.isEmpty()) {
            throw json.malformed("'options' must be a non-empty array");
        }
        List<Option> result = new ArrayList<>();
        for (JsonNode element : value) {
            if (element.isTextual()) {
                if (element.asText().isBlank()) {
                    throw json.malformed("a choice option string must be non-empty");
                }
                result.add(new Option(element.asText(), null));
            } else if (element.isObject()) {
                result.add(new Option(
                        json.requireText(element, "text"),
                        json.optionalText(element, "misconception")));
            } else {
                throw json.malformed(
                        "each choice option must be a string or an object "
                                + "{ text, misconception }");
            }
        }
        return result;
    }

    /**
     * The competitive-distractor gate (issue #42), enforced mechanically at load time:
     * for a choice judged by an answer key, every wrong option (distractor) must declare
     * the specific misconception it targets. A missing misconception fails the check
     * naming the exact option, so a giveaway option that no learner would plausibly pick -
     * or one an author simply forgot to annotate - never loads. It also catches an answer
     * key that matches none of the options (an unanswerable check).
     *
     * <p>This is the "was the misconception <em>declared</em>" half of the bar, which is a
     * machine question; whether a declared misconception is genuinely <em>plausible</em> is
     * the human red-team judgement the authoring checklist owns. Only Choice + AnswerKey is
     * gated, since only there is one option the correct answer and the rest distractors.
     */
    private static void validateDistractors(ContentJson json, Response response, Grading grading) {
        if (!(response instanceof Response.Choice choice)
                || !(grading instanceof Grading.AnswerKey key)) {
            return;
        }
        boolean anyCorrect = false;
        for (Option option : choice.options()) {
            if (ChoiceKeys.matchesKey(key, option.text(), MAPPER)) {
                anyCorrect = true;
            } else if (!option.hasMisconception()) {
                throw json.malformed(
                        "choice option '" + option.text() + "' is a distractor but declares no "
                                + "target misconception - every distractor must name the specific "
                                + "misconception it encodes (issue #42)");
            }
        }
        if (!anyCorrect) {
            throw json.malformed(
                    "the answer key '" + key.expected().asText() + "' matches none of the choice "
                            + "options, so the check has no correct answer");
        }
    }
}
