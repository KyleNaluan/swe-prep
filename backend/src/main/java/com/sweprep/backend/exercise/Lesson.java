package com.sweprep.backend.exercise;

import java.time.LocalDate;
import java.util.List;

/**
 * Taught content: explanatory text that says what a fundamental, technology or
 * practice is and what it is <em>for</em> (issue #46). A lesson is the taught half
 * of the second content track, sibling to {@link Exercise} under {@link Content}.
 *
 * <p>A lesson is <strong>read, never attempted</strong>. It deliberately carries no
 * {@code Response} (there is nothing to answer) and no {@code Grading} (there is
 * nothing to judge), so it can never be graded or produce a verdict - the type
 * itself is the boundary, not a check in a caller. Its {@link #statement()} is the
 * lesson body.
 *
 * <p>A lesson references its {@link #checks()} by id: the {@code Form = REP}
 * exercises that verify it. They stay ordinary reps and flow through the existing
 * rep pipeline untouched; the lesson only names them, holding no exercise of its
 * own. The reference is by id because content lives file-per-item, and the same
 * public/private split (issue #4/#14) that keeps an attempt's {@code exercise_id} a
 * plain text id applies here.
 *
 * @param id         stable identifier, unique across the content set
 * @param title      short title shown above the body
 * @param statement  the lesson body, as Markdown-friendly plain text
 * @param domain     the domain it belongs to, e.g. {@code "fundamentals"}
 * @param topics     topic tags used later by the scheduler; never assumed non-empty
 * @param difficulty how hard the material is
 * @param checks     ids of the {@code Form = REP} exercises this lesson introduces;
 *                   empty when the lesson has no checks authored yet
 * @param prompts    ungraded self-explanation prompts embedded in the lesson (issue #41);
 *                   each is read, thought about, then its model answer revealed - part of
 *                   active reading, never an attempt. Empty when the lesson has none.
 * @param family     the role families this content serves (design revision t3)
 * @param stability  how durable the subject matter is; {@link Stability#STABLE}
 *                   unless it rots
 * @param reviewed   the date {@link Stability#VOLATILE} content was last re-verified;
 *                   {@code null} for stable content
 */
public record Lesson(
        String id,
        String title,
        String statement,
        String domain,
        List<String> topics,
        Difficulty difficulty,
        List<String> checks,
        List<SelfExplainPrompt> prompts,
        List<Family> family,
        Stability stability,
        LocalDate reviewed) implements Content {

    public Lesson {
        topics = List.copyOf(topics);
        checks = List.copyOf(checks);
        prompts = List.copyOf(prompts);
        family = List.copyOf(family);
        stability = stability == null ? Stability.STABLE : stability;
    }

    /**
     * Convenience constructor for a lesson with no self-explanation prompts and no
     * content-level family or stability tags: an empty family, {@link Stability#STABLE},
     * and no review date. These are additive metadata (design revision t3, issue #41); an
     * untagged lesson is a stable one with no family and no prompts.
     */
    public Lesson(
            String id,
            String title,
            String statement,
            String domain,
            List<String> topics,
            Difficulty difficulty,
            List<String> checks) {
        this(id, title, statement, domain, topics, difficulty, checks, List.of(), List.of(),
                Stability.STABLE, null);
    }
}
