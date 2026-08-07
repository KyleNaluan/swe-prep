package com.sweprep.backend.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.sweprep.backend.exercise.Difficulty;
import com.sweprep.backend.exercise.Lesson;
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
 *   "family":   ["BACKEND"],                           // optional, default []
 *   "stability": "STABLE|VOLATILE",                    // optional, default STABLE
 *   "reviewed": "2026-08-07"                           // optional ISO date (VOLATILE)
 * }
 * </pre>
 *
 * <p>The {@code prompts} are ungraded self-explanation prompts (issue #41): part of active
 * reading, not an attempt. They carry no response spec and no grader - a lesson stays
 * {@code READ}.
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
        return new Lesson(
                id, title, statement, domain, topics, difficulty, checks, prompts,
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
