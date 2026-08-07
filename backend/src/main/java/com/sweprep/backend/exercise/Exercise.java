package com.sweprep.backend.exercise;

import java.time.LocalDate;
import java.util.List;

/**
 * An exercise, independent of any language.
 *
 * <p>This is the domain model the abstraction decision (issue #6) makes real: an
 * exercise carries a prompt, the {@link Difficulty} and {@link #topics()} the
 * scheduler will weigh, its {@link Form} (a rep or a challenge, an attribute here
 * rather than a subtype), a {@link Response} spec saying how it is answered, and a
 * {@link Grading} spec saying how the answer is judged. It says nothing about how
 * it is executed or graded: the response drives a language adapter, and the
 * grading selects a grader (which may need no runner at all). Keeping all of this
 * out of the exercise is what lets a concept question and a coding problem share
 * one model, one loader and one seam.
 *
 * <p>An exercise is one kind of {@link Content}: it is <em>attempted</em>, so its
 * {@link #response()} and {@link #grading()} are always present, unlike a {@link
 * Lesson}, which is only read (issue #46). {@code Exercise} implements {@code
 * Content} additively - it already carried every accessor the supertype names.
 *
 * @param id         stable identifier, unique across the content set
 * @param title      short title shown above the prompt
 * @param statement  the prompt, as Markdown-friendly plain text
 * @param domain     the domain it belongs to, e.g. {@code "algorithms"} or
 *                   {@code "fundamentals"}; the session is domain-agnostic (issue #3)
 * @param topics     topic tags used later by the scheduler; never assumed non-empty
 * @param difficulty how hard it is
 * @param form       whether it is a {@link Form#REP} or a {@link Form#CHALLENGE}
 * @param response   how the solver answers it
 * @param grading    how the answer is judged
 * @param hints      the ordered hint ladder (issue #16), least revealing rung first;
 *                   empty when the exercise offers no hints
 * @param explanation why the correct answer is correct (issue #51), independent of the
 *                   hint ladder: shown automatically on a wrong answer and available on
 *                   request when correct, where asking is recorded as a distinct
 *                   confidence signal. Never a hint - a hint is disclosed only on
 *                   request and its taking is a different signal. {@code null} when the
 *                   check carries none (a self-check's model answer already plays this
 *                   role, so it needs no separate explanation)
 * @param family     the role families this content serves (design revision t3,
 *                   section 2), the tag the family filter selects on; empty when
 *                   untagged, and a list because one concept can serve several roles
 * @param stability  how durable the subject matter is (design revision t3, section
 *                   3.4); {@link Stability#STABLE} unless the content rots
 * @param reviewed   the date {@link Stability#VOLATILE} content was last
 *                   re-verified; {@code null} for stable content, which needs none
 * @param derivedFrom the id of the underlying problem this rep was derived from
 *                   (issue #9: nearly every rep falls out of a challenge's reference
 *                   solution), used only by the warm-up selector to gate the rep: a
 *                   derived rep (complexity, fill-in-the-blank, predict-output,
 *                   spot-the-bug) is only served once that problem has been attempted,
 *                   since practising it cold is guessing. {@code null} for a rep that is
 *                   available cold - the pattern-identification type, whose whole point
 *                   is that recognising a shape needs no prior attempt (issue #18). Only
 *                   meaningful for a {@link Form#REP}; a challenge carries {@code null}
 */
public record Exercise(
        String id,
        String title,
        String statement,
        String domain,
        List<String> topics,
        Difficulty difficulty,
        Form form,
        Response response,
        Grading grading,
        List<Hint> hints,
        String explanation,
        List<Family> family,
        Stability stability,
        LocalDate reviewed,
        String derivedFrom) implements Content {

    public Exercise {
        topics = List.copyOf(topics);
        hints = List.copyOf(hints);
        family = List.copyOf(family);
        stability = stability == null ? Stability.STABLE : stability;
    }

    /**
     * Convenience constructor for an exercise with no explanation and no content-level
     * family or stability tags: no explanation, an empty family, {@link
     * Stability#STABLE}, no review date, and no {@code derivedFrom} gate (available
     * cold). These are additive metadata (issue #51, design revision t3, issue #18); an
     * untagged exercise is a stable one with no family, one with no {@code explanation}
     * carries {@code null}, and one with no {@code derivedFrom} is served without gating.
     */
    public Exercise(
            String id,
            String title,
            String statement,
            String domain,
            List<String> topics,
            Difficulty difficulty,
            Form form,
            Response response,
            Grading grading,
            List<Hint> hints) {
        this(
                id, title, statement, domain, topics, difficulty, form, response, grading, hints,
                null, List.of(), Stability.STABLE, null, null);
    }
}
