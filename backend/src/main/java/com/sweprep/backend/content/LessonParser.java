package com.sweprep.backend.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.sweprep.backend.exercise.CalloutKind;
import com.sweprep.backend.exercise.Difficulty;
import com.sweprep.backend.exercise.Lesson;
import com.sweprep.backend.exercise.LessonBlock;
import com.sweprep.backend.exercise.SelfExplainPrompt;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a {@link Lesson} from the JSON one content file holds when its {@code kind}
 * is {@code "lesson"} (issue #46). It shares the metadata reads with {@link
 * ExerciseParser} through {@link ContentJson} and adds the one lesson-only field -
 * the {@code checks} it references by id - so a malformed lesson fails naming the
 * file and field exactly as a malformed exercise does.
 *
 * <p>A lesson has <strong>no response and no grading</strong> by design (it is read,
 * not attempted), so this parser never reads those fields; the absence is the model,
 * not an omission.
 *
 * <p>The format:
 * <pre>
 * {
 *   "kind": "lesson",
 *   "id": "...", "title": "...", "statement": "...",
 *   "domain": "fundamentals", "topics": ["messaging"],
 *   "difficulty": "EASY|MEDIUM|HARD",
 *   "checks": ["mq-when-to-use", "mq-vs-direct-call"], // ids of its REP exercises
 *   "prompts": [ { "prompt": "Explain why...", "modelAnswer": "..." }, ... ], // optional (issue #41)
 *   "body": [                                           // optional (issue #90 follow-on), see below
 *     { "kind": "heading", "level": 2, "text": "..." },
 *     { "kind": "paragraph", "text": "..." },
 *     { "kind": "example", "language": "java", "code": "...", "caption": "...", "output": "..." },
 *     { "kind": "callout", "style": "NOTE|TIP|WARNING", "text": "..." },
 *     { "kind": "list", "ordered": false, "items": ["...", "..."] },
 *     { "kind": "table", "headers": ["...", "..."], "rows": [["...", "..."]] }
 *   ],
 *   "family":   ["BACKEND"],                           // optional, default []
 *   "stability": "STABLE|VOLATILE",                    // optional, default STABLE
 *   "reviewed": "2026-08-07"                           // optional ISO date (VOLATILE)
 * }
 * </pre>
 *
 * <p>The {@code prompts} are ungraded self-explanation prompts (issue #41): part of active
 * reading, not an attempt. They carry no response spec and no grader - a lesson stays
 * {@code READ}.
 *
 * <p>{@code body} is the structured lesson content (issue #90 follow-on visual redesign):
 * an ordered list of {@link LessonBlock}s, each an object with its own {@code "kind"}
 * discriminator - the same shape {@code response}/{@code grading} already use elsewhere in
 * this format. It is entirely optional and additive: absent or empty means the lesson still
 * carries only {@code statement}, and the app renders that as it always has (a single
 * paragraph). {@code caption}/{@code output} on an {@code example} block are each optional
 * independently. See {@code LessonBlock} for what each block kind means and how the
 * renderer treats it.
 */
final class LessonParser {

    private LessonParser() {}

    /** Parse one lesson; {@code source} names the file for error messages. */
    static Lesson parse(String source, JsonNode root) {
        ContentJson json = new ContentJson(source, "lesson");
        String id = json.requireText(root, "id");
        String title = json.requireText(root, "title");
        String statement = json.requireText(root, "statement");
        String domain = json.requireText(root, "domain");
        List<String> topics = json.topics(root);
        Difficulty difficulty = json.requireEnum(root, "difficulty", Difficulty.class);
        List<String> checks = checks(json, root);
        List<SelfExplainPrompt> prompts = prompts(json, root);
        List<LessonBlock> body = body(json, root);
        return new Lesson(
                id, title, statement, domain, topics, difficulty, checks, prompts, body,
                json.family(root), json.stability(root), json.reviewed(root));
    }

    /**
     * The optional self-explanation prompts (issue #41). Absent means none; when present it
     * must be an array of {@code { prompt, modelAnswer }} objects, each with both fields, so
     * a malformed prompt fails naming the file and field exactly as any other content does.
     */
    private static List<SelfExplainPrompt> prompts(ContentJson json, JsonNode root) {
        JsonNode node = root.get("prompts");
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw json.malformed("'prompts' must be an array of { prompt, modelAnswer } objects");
        }
        List<SelfExplainPrompt> prompts = new ArrayList<>();
        for (JsonNode prompt : node) {
            if (!prompt.isObject()) {
                throw json.malformed("each prompt must be an object with 'prompt' and 'modelAnswer'");
            }
            prompts.add(new SelfExplainPrompt(
                    json.requireText(prompt, "prompt"), json.requireText(prompt, "modelAnswer")));
        }
        return prompts;
    }

    /**
     * The optional structured body (issue #90 follow-on). Absent or empty means the
     * lesson has no structured blocks yet and stays statement-only - the renderer's
     * legacy fallback, not an error. When present, each entry is an object with its
     * own {@code "kind"} discriminator, exactly like every other sealed-hierarchy
     * field this format already uses ({@code response}, {@code grading}, a
     * complexity generator argument).
     */
    private static List<LessonBlock> body(ContentJson json, JsonNode root) {
        JsonNode node = root.get("body");
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw json.malformed("'body' must be an array of lesson blocks");
        }
        List<LessonBlock> blocks = new ArrayList<>();
        for (JsonNode blockNode : node) {
            blocks.add(block(json, blockNode));
        }
        return blocks;
    }

    private static LessonBlock block(ContentJson json, JsonNode node) {
        if (!node.isObject()) {
            throw json.malformed("each 'body' entry must be an object");
        }
        String kind = json.requireText(node, "kind");
        try {
            return switch (kind) {
                case "heading" -> heading(json, node);
                case "paragraph" -> new LessonBlock.Paragraph(json.requireText(node, "text"));
                case "example" -> example(json, node);
                case "callout" -> new LessonBlock.Callout(
                        json.requireEnum(node, "style", CalloutKind.class), json.requireText(node, "text"));
                case "list" -> list(json, node);
                case "table" -> table(json, node);
                default -> throw json.malformed("unknown lesson body block kind '" + kind + "'");
            };
        } catch (IllegalArgumentException e) {
            throw json.malformed(e.getMessage());
        }
    }

    /** {@code level} is optional, defaulting to 2 (an h2) - most lessons need no h3. */
    private static LessonBlock.Heading heading(ContentJson json, JsonNode node) {
        JsonNode levelNode = node.get("level");
        int level = levelNode == null || levelNode.isNull() ? 2 : levelNode.asInt();
        return new LessonBlock.Heading(level, json.requireText(node, "text"));
    }

    /** {@code caption} and {@code output} are each independently optional. */
    private static LessonBlock.Example example(ContentJson json, JsonNode node) {
        return new LessonBlock.Example(
                json.requireText(node, "language"),
                json.requireText(node, "code"),
                json.optionalText(node, "caption"),
                json.optionalText(node, "output"));
    }

    private static LessonBlock.ListBlock list(ContentJson json, JsonNode node) {
        JsonNode orderedNode = node.get("ordered");
        boolean ordered = orderedNode != null && orderedNode.asBoolean(false);
        JsonNode itemsNode = json.requireField(node, "items");
        if (!itemsNode.isArray()) {
            throw json.malformed("'items' must be an array of strings");
        }
        List<String> items = new ArrayList<>();
        for (JsonNode item : itemsNode) {
            if (!item.isTextual() || item.asText().isBlank()) {
                throw json.malformed("'items' must contain only non-empty strings");
            }
            items.add(item.asText());
        }
        return new LessonBlock.ListBlock(ordered, items);
    }

    /** {@code rows} is optional (defaulting to none) - a table may be header-only. */
    private static LessonBlock.Table table(ContentJson json, JsonNode node) {
        JsonNode headersNode = json.requireField(node, "headers");
        if (!headersNode.isArray()) {
            throw json.malformed("'headers' must be an array of strings");
        }
        List<String> headers = new ArrayList<>();
        for (JsonNode header : headersNode) {
            if (!header.isTextual() || header.asText().isBlank()) {
                throw json.malformed("'headers' must contain only non-empty strings");
            }
            headers.add(header.asText());
        }
        JsonNode rowsNode = node.get("rows");
        List<List<String>> rows = new ArrayList<>();
        if (rowsNode != null && !rowsNode.isNull()) {
            if (!rowsNode.isArray()) {
                throw json.malformed("'rows' must be an array of rows");
            }
            for (JsonNode rowNode : rowsNode) {
                if (!rowNode.isArray()) {
                    throw json.malformed("each table row must be an array of cell strings");
                }
                List<String> row = new ArrayList<>();
                for (JsonNode cell : rowNode) {
                    if (!cell.isTextual()) {
                        throw json.malformed("each table cell must be a string");
                    }
                    row.add(cell.asText());
                }
                rows.add(row);
            }
        }
        return new LessonBlock.Table(headers, rows);
    }

    /**
     * The check references. Required so the defining "a lesson references its checks
     * by id" is explicit in every lesson file, but permitted to be empty for a lesson
     * whose checks are not authored yet.
     */
    private static List<String> checks(ContentJson json, JsonNode root) {
        JsonNode node = json.requireField(root, "checks");
        if (!node.isArray()) {
            throw json.malformed("'checks' must be an array of exercise ids");
        }
        List<String> checks = new ArrayList<>();
        for (JsonNode check : node) {
            if (!check.isTextual() || check.asText().isBlank()) {
                throw json.malformed("'checks' must contain only non-empty exercise ids");
            }
            checks.add(check.asText());
        }
        return checks;
    }
}
