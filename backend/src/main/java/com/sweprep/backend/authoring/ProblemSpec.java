package com.sweprep.backend.authoring;

import com.sweprep.backend.exercise.ComplexityCheck;
import com.sweprep.backend.exercise.Comparison;
import com.sweprep.backend.exercise.Difficulty;
import com.sweprep.backend.exercise.Family;
import com.sweprep.backend.exercise.Hint;
import com.sweprep.backend.exercise.Signature;
import com.sweprep.backend.exercise.TestCase;
import java.time.LocalDate;
import java.util.List;

/**
 * The authoring unit issue #24 names: statement, test cases, a reference solution
 * and (optionally) an input generator, exactly the shape a human author supplies
 * once so the tool can derive everything else.
 *
 * <p>This is deliberately close to the final {@code CHALLENGE} exercise JSON
 * ({@code ExerciseParser}'s format) rather than a new shape to learn - the
 * difference is that {@link Signature}, {@link #comparison()} and {@link
 * #cases()} sit at the top level instead of nested under {@code response}/{@code
 * grading} (a real exercise never carries both a {@code "statement"} and a
 * top-level {@code "cases"} array, so this file can never be mistaken for
 * committed content by {@code scripts/check-no-content.sh}), and it adds exactly
 * one authoring-only field: {@link #referenceSolution()}, the Java source the
 * derivation pipeline compiles, runs and mutates. That field is never written to
 * the emitted exercise JSON - it is copied to {@code solutions/<id>.java}
 * instead, mirroring the convention the content repo's seeded files already use.
 *
 * @param id                 stable id, becomes the challenge exercise's id and the
 *                            stem every derived rep id and {@code derivedFrom} builds on
 * @param title               short title
 * @param statement           the prompt, Markdown-friendly plain text
 * @param domain              e.g. {@code "algorithms"}
 * @param topics              topic tags; the first tag the pattern catalog recognises
 *                            drives the pattern-identification rep (issue #9)
 * @param difficulty          how hard the challenge is
 * @param signature           the method the solver implements and the reference solves
 * @param comparison          how a submission is judged against {@link #cases()}
 * @param cases               the hand-authored test cases
 * @param referenceSolution   full Java source of a {@code Solution} class that
 *                            correctly solves every one of {@link #cases()} -
 *                            verified, not assumed, before any rep is derived
 * @param hints               optional hint ladder for the challenge itself
 * @param explanation         optional explanation for the challenge itself
 * @param family              optional role-family tags, applied to the challenge and
 *                            every rep derived from it
 * @param stability           defaults to {@code STABLE} when {@code null}
 * @param reviewed            optional last-reviewed date for {@code VOLATILE} content
 * @param complexityCheck     optional issue #17 self-report target for the challenge;
 *                            unrelated to the derived <em>complexity rep</em> (issue #9),
 *                            which asks the solver to classify a snippet's Big-O rather
 *                            than self-report and empirically check the challenge's own
 */
public record ProblemSpec(
        String id,
        String title,
        String statement,
        String domain,
        List<String> topics,
        Difficulty difficulty,
        Signature signature,
        Comparison comparison,
        List<TestCase> cases,
        String referenceSolution,
        List<Hint> hints,
        String explanation,
        List<Family> family,
        com.sweprep.backend.exercise.Stability stability,
        LocalDate reviewed,
        ComplexityCheck complexityCheck) {

    public ProblemSpec {
        topics = List.copyOf(topics);
        cases = List.copyOf(cases);
        hints = hints == null ? List.of() : List.copyOf(hints);
        family = family == null ? List.of() : List.copyOf(family);
        stability = stability == null ? com.sweprep.backend.exercise.Stability.STABLE : stability;
        if (id == null || id.isBlank()) {
            throw new AuthoringException("problem spec is missing a non-blank 'id'");
        }
        if (referenceSolution == null || referenceSolution.isBlank()) {
            throw new AuthoringException("problem spec '" + id + "' is missing 'referenceSolution'");
        }
        if (cases.isEmpty()) {
            throw new AuthoringException("problem spec '" + id + "' must declare at least one case");
        }
    }
}
