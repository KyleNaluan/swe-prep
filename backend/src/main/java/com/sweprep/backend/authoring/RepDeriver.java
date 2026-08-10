package com.sweprep.backend.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sweprep.backend.authoring.MutationCatalog.MutationCandidate;
import com.sweprep.backend.authoring.ReferenceExecutor.RunResult;
import com.sweprep.backend.exercise.Comparison;
import com.sweprep.backend.exercise.Complexity;
import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.Form;
import com.sweprep.backend.exercise.Grading;
import com.sweprep.backend.exercise.Option;
import com.sweprep.backend.exercise.Response;
import com.sweprep.backend.exercise.TestCase;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The derivation pipeline itself (issue #24): given one {@link ProblemSpec} - the
 * authoring unit of statement, cases, and a reference solution - produces the
 * {@code CHALLENGE} exercise plus the warm-up reps that fall out of it, verifying
 * the reference solution empirically before deriving anything and never guessing
 * where a derivation cannot be made with confidence (each such gap is reported,
 * not silently filled).
 *
 * <p>Every rep type derives mechanically, matching the task's "deterministic
 * where possible" instruction with no LLM dependency:
 *
 * <ul>
 *   <li><b>Pattern-identification</b> - from the problem's own declared {@code
 *       topics} via {@link PatternCatalog} (from the <em>statement</em>'s
 *       metadata, per issue #9), available cold ({@code derivedFrom} null).
 *   <li><b>Complexity</b> - from a structural estimate of the reference solution
 *       ({@link ComplexityHeuristic}); skipped rather than guessed when the
 *       solution is recursive or its nesting is not confidently classifiable.
 *   <li><b>Fill-in-the-blank</b> - a line of the reference solution is blanked;
 *       the distractors are {@link MutationCatalog} variants of that same line,
 *       each empirically verified (like spot-the-bug) to break a declared case, so
 *       a behavior-preserving mutation can never become a second correct answer.
 *   <li><b>Spot-the-bug</b> - {@link MutationCatalog} candidates are tried in a
 *       fixed order until one both <em>compiles</em> and <em>empirically fails a
 *       declared case</em> when run through the exact same {@link
 *       ReferenceExecutor} the reference solution itself was verified with; the
 *       correct option's text is generated from that mutation's line-and-diff
 *       (issue #24's "mutation recorded as the answer").
 *   <li><b>Predict-output</b> - the reference solution is actually run (via
 *       {@link ReferenceExecutor#callOnce}) on its own smallest declared case,
 *       and the value it returns becomes the answer key.
 * </ul>
 */
final class RepDeriver {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ReferenceExecutor executor;

    RepDeriver() {
        this(new ReferenceExecutor());
    }

    RepDeriver(ReferenceExecutor executor) {
        this.executor = executor;
    }

    /** One derivation run: the challenge, the reps that could be derived, and what was skipped and why. */
    record DerivationResult(Exercise challenge, List<Exercise> reps, List<String> skipped) {}

    DerivationResult derive(ProblemSpec spec) {
        verifyReferenceSolution(spec);

        Exercise challenge = buildChallenge(spec);
        List<Exercise> reps = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        derivePatternRep(spec).ifPresentOrElse(
                reps::add, () -> skipped.add("pattern-identification: no topic in " + spec.topics()
                        + " names a recognised pattern"));
        deriveComplexityRep(spec).ifPresentOrElse(
                reps::add, () -> skipped.add("complexity: the reference solution is recursive or its "
                        + "loop nesting could not be confidently classified"));
        deriveFillBlankRep(spec).ifPresentOrElse(
                reps::add, () -> skipped.add("fill-in-the-blank: no line of the reference solution "
                        + "yielded three distinct variants that each empirically break a declared case"));
        deriveSpotBugRep(spec).ifPresentOrElse(
                reps::add, () -> skipped.add("spot-the-bug: no candidate mutation both compiled and "
                        + "empirically failed a declared case"));
        reps.add(derivePredictOutputRep(spec));

        return new DerivationResult(challenge, List.copyOf(reps), List.copyOf(skipped));
    }

    /**
     * The gate every derivation depends on: the reference solution must actually
     * solve the declared cases under the declared comparison, checked by running it
     * through the same harness a learner's submission would run through. A reference
     * solution that fails its own cases is an authoring error in the problem spec,
     * not something reps should be derived from.
     */
    private void verifyReferenceSolution(ProblemSpec spec) {
        RunResult result = executor.run(spec.signature(), spec.referenceSolution(), spec.cases());
        switch (result) {
            case RunResult.CompileError error -> throw new AuthoringException(
                    "reference solution for '" + spec.id() + "' does not compile:\n" + error.message());
            case RunResult.TimedOut ignored -> throw new AuthoringException(
                    "reference solution for '" + spec.id() + "' timed out running its own declared cases");
            case RunResult.Completed completed -> {
                if (!completed.allPass(spec.comparison())) {
                    throw new AuthoringException(
                            "reference solution for '" + spec.id() + "' does not pass its own declared "
                                    + "cases - fix the solution or the cases before deriving reps");
                }
            }
        }
    }

    private Exercise buildChallenge(ProblemSpec spec) {
        return new Exercise(
                spec.id(), spec.title(), spec.statement(), spec.domain(), spec.topics(), spec.difficulty(),
                Form.CHALLENGE, new Response.Code(spec.signature()),
                new Grading.TestCases(spec.comparison(), spec.cases()), spec.hints(), spec.explanation(),
                spec.family(), spec.stability(), spec.reviewed(), null, spec.complexityCheck());
    }

    // ------------------------------------------------------------------ pattern-id

    private Optional<Exercise> derivePatternRep(ProblemSpec spec) {
        Optional<String> correct = PatternCatalog.patternFor(spec.topics());
        if (correct.isEmpty()) {
            return Optional.empty();
        }
        String label = correct.get();
        List<PatternCatalog.Distractor> distractors = PatternCatalog.distractors(label, 3);
        if (distractors.size() < 3) {
            return Optional.empty();
        }
        List<Option> options = new ArrayList<>();
        options.add(Option.correct(label));
        for (PatternCatalog.Distractor distractor : distractors) {
            options.add(Option.distractor(distractor.label(), distractor.misconception()));
        }
        String statement = spec.statement() + "\n\nWhich pattern fits this problem best?";
        String explanation = label + " fits: the problem is tagged with a topic that names it directly, "
                + "and none of the other candidate patterns' preconditions hold here.";
        return Optional.of(rep(
                spec, "pattern", "Pattern: " + spec.title(), statement, new Response.Choice(options),
                new Grading.AnswerKey(text(label), Comparison.exact()), explanation, null));
    }

    // ------------------------------------------------------------------ complexity

    private Optional<Exercise> deriveComplexityRep(ProblemSpec spec) {
        Optional<Complexity> correctOpt =
                ComplexityHeuristic.estimate(spec.referenceSolution(), spec.signature().methodName());
        if (correctOpt.isEmpty()) {
            return Optional.empty();
        }
        Complexity correct = correctOpt.get();
        List<Complexity> distractors = ComplexityHeuristic.nearestOthers(correct, 3);
        if (distractors.size() < 3) {
            return Optional.empty();
        }
        String correctLabel = ComplexityHeuristic.label(correct);
        List<Option> options = new ArrayList<>();
        options.add(Option.correct(correctLabel));
        for (Complexity distractor : distractors) {
            options.add(Option.distractor(
                    ComplexityHeuristic.label(distractor), ComplexityHeuristic.misconception(correct, distractor)));
        }
        String statement = "What is the time complexity of `" + spec.signature().methodName()
                + "` below?\n\n```java\n" + spec.referenceSolution() + "\n```";
        String explanation = "The method's loop nesting makes it " + correctLabel + " ("
                + ComplexityHeuristic.describe(correct) + ") in the input size.";
        return Optional.of(rep(
                spec, "complexity", "Complexity: " + spec.title(), statement, new Response.Choice(options),
                new Grading.AnswerKey(text(correctLabel), Comparison.exact()), explanation, spec.id()));
    }

    // ------------------------------------------------------------------ fill-in-the-blank

    private Optional<Exercise> deriveFillBlankRep(ProblemSpec spec) {
        List<MutationCandidate> candidates = MutationCatalog.candidates(spec.referenceSolution());
        Map<Integer, List<MutationCandidate>> byLine = new LinkedHashMap<>();
        for (MutationCandidate candidate : candidates) {
            byLine.computeIfAbsent(candidate.lineIndex(), k -> new ArrayList<>()).add(candidate);
        }
        for (Map.Entry<Integer, List<MutationCandidate>> entry : byLine.entrySet()) {
            List<MutationCandidate> verified =
                    verifiedBreakingVariants(spec, dedupeByMutatedLine(entry.getValue()), 3);
            if (verified.size() >= 3) {
                return Optional.of(buildFillBlankRep(spec, entry.getKey(), verified));
            }
        }
        return Optional.empty();
    }

    private List<MutationCandidate> dedupeByMutatedLine(List<MutationCandidate> candidates) {
        Map<String, MutationCandidate> byMutated = new LinkedHashMap<>();
        for (MutationCandidate candidate : candidates) {
            byMutated.putIfAbsent(candidate.mutatedLine().strip(), candidate);
        }
        return List.copyOf(byMutated.values());
    }

    /**
     * The candidates whose mutated source actually breaks a declared case - the same
     * empirical gate spot-the-bug applies, so a fill-in-the-blank distractor is never a
     * behavior-preserving change that would make the rep have two correct answers. A
     * candidate is accepted only when its mutated source is not a completed run that
     * still passes every case (a compile error, a timeout, or any failing case all
     * count as broken). Returns as soon as {@code limit} are found.
     */
    private List<MutationCandidate> verifiedBreakingVariants(
            ProblemSpec spec, List<MutationCandidate> candidates, int limit) {
        List<MutationCandidate> verified = new ArrayList<>();
        for (MutationCandidate candidate : candidates) {
            RunResult result = executor.run(
                    spec.signature(), candidate.applyTo(spec.referenceSolution()), spec.cases());
            boolean breaksSolution =
                    !(result instanceof RunResult.Completed completed) || completed.anyFail(spec.comparison());
            if (breaksSolution) {
                verified.add(candidate);
                if (verified.size() == limit) {
                    break;
                }
            }
        }
        return verified;
    }

    private Exercise buildFillBlankRep(ProblemSpec spec, int lineIndex, List<MutationCandidate> variants) {
        MutationCandidate any = variants.get(0);
        String correctLine = any.originalLine().strip();
        String blanked = blankLine(spec.referenceSolution(), lineIndex);
        String statement = "Fill in the blank in this solution to '" + spec.title() + "':\n\n```java\n"
                + blanked + "\n```";

        List<Option> options = new ArrayList<>();
        options.add(Option.correct(correctLine));
        for (MutationCandidate variant : variants.subList(0, 3)) {
            options.add(Option.distractor(
                    variant.mutatedLine().strip(),
                    "reflects " + variant.category().label + ": " + variant.category().genericDescription));
        }
        String explanation = "`" + correctLine + "` is the line the reference solution actually uses; "
                + "each other option changes it in a way that breaks the solution (see each option's "
                + "target misconception).";
        return rep(
                spec, "fill-blank", "Fill the blank: " + spec.title(), statement, new Response.Choice(options),
                new Grading.AnswerKey(text(correctLine), Comparison.exact()), explanation, spec.id());
    }

    private String blankLine(String source, int lineIndex) {
        String[] lines = source.split("\n", -1);
        String original = lines[lineIndex];
        String indent = original.substring(0, original.length() - original.stripLeading().length());
        lines[lineIndex] = indent + "___";
        return String.join("\n", lines);
    }

    // ------------------------------------------------------------------ spot-the-bug

    private Optional<Exercise> deriveSpotBugRep(ProblemSpec spec) {
        List<MutationCandidate> candidates = MutationCatalog.candidates(spec.referenceSolution());
        for (MutationCandidate candidate : candidates) {
            String mutatedSource = candidate.applyTo(spec.referenceSolution());
            RunResult result = executor.run(spec.signature(), mutatedSource, spec.cases());
            if (!(result instanceof RunResult.Completed completed) || !completed.anyFail(spec.comparison())) {
                continue;
            }
            List<MutationCandidate> distractorPool = dedupeByDescription(candidates, candidate);
            if (distractorPool.size() < 3) {
                continue;
            }
            return Optional.of(buildSpotBugRep(spec, candidate, mutatedSource, completed, distractorPool));
        }
        return Optional.empty();
    }

    /** Every other candidate, deduped by its description text, excluding the winning one. */
    private List<MutationCandidate> dedupeByDescription(List<MutationCandidate> candidates, MutationCandidate winner) {
        Map<String, MutationCandidate> byDescription = new LinkedHashMap<>();
        for (MutationCandidate candidate : candidates) {
            if (!candidate.describe().equals(winner.describe())) {
                byDescription.putIfAbsent(candidate.describe(), candidate);
            }
        }
        return List.copyOf(byDescription.values());
    }

    private Exercise buildSpotBugRep(
            ProblemSpec spec,
            MutationCandidate winner,
            String mutatedSource,
            RunResult.Completed mutatedRun,
            List<MutationCandidate> distractorPool) {
        String statement = "This solution to '" + spec.title() + "' has a bug:\n\n```java\n" + mutatedSource
                + "\n```\n\nWhat is wrong with it?";
        String correctText = winner.describe() + " (" + winner.category().genericDescription + ".)";

        List<Option> options = new ArrayList<>();
        options.add(Option.correct(correctText));
        for (MutationCandidate distractor : distractorPool.subList(0, 3)) {
            String text = distractor.describe() + " (" + distractor.category().genericDescription + ".)";
            options.add(Option.distractor(
                    text, "believes " + distractor.category().label + " is the defect, which is not the "
                            + "change actually made here"));
        }

        String failingCaseNote = mutatedRun.cases().stream()
                .filter(c -> !c.matches(spec.comparison()))
                .findFirst()
                .map(c -> " calling " + spec.signature().methodName() + " on " + c.testCase().input()
                        + " now returns " + (c.threw() ? "an exception" : c.returned())
                        + " instead of the expected " + c.testCase().expected() + ".")
                .orElse("");
        String explanation = correctText + failingCaseNote;

        return rep(
                spec, "spot-bug", "Spot the bug: " + spec.title(), statement, new Response.Choice(options),
                new Grading.AnswerKey(text(correctText), Comparison.exact()), explanation, spec.id());
    }

    // ------------------------------------------------------------------ predict-output

    private Exercise derivePredictOutputRep(ProblemSpec spec) {
        TestCase smallest = spec.cases().stream()
                .min(Comparator.comparingInt(c -> c.input().toString().length()))
                .orElseThrow();
        ReferenceExecutor.RunResult.CaseOutcome outcome =
                executor.callOnce(spec.signature(), spec.referenceSolution(), smallest.input());
        if (outcome.threw() || outcome.returned() == null) {
            throw new AuthoringException(
                    "predict-output derivation for '" + spec.id() + "' could not read a returned value for "
                            + "its own smallest case, despite the reference solution passing every declared "
                            + "case - this should not happen");
        }
        JsonNode expected = outcome.returned();
        String call = spec.signature().methodName() + "(" + formatArgs(smallest.input()) + ")";
        String statement = "Given this solution to '" + spec.title() + "', what does `" + call
                + "` return?\n\n```java\n" + spec.referenceSolution() + "\n```\n\nType the exact value.";
        String explanation = "Running the reference solution on this input returns `" + expected + "`.";
        return rep(
                spec, "predict-output", "Predict output: " + spec.title(), statement, new Response.FreeText(),
                new Grading.AnswerKey(expected, Comparison.exact()), explanation, spec.id());
    }

    private String formatArgs(JsonNode input) {
        List<String> parts = new ArrayList<>();
        input.forEach(arg -> parts.add(arg.toString()));
        return String.join(", ", parts);
    }

    // ------------------------------------------------------------------ shared

    private Exercise rep(
            ProblemSpec spec,
            String idSuffix,
            String title,
            String statement,
            Response response,
            Grading grading,
            String explanation,
            String derivedFrom) {
        return new Exercise(
                spec.id() + "-" + idSuffix, title, statement, spec.domain(), spec.topics(), spec.difficulty(),
                Form.REP, response, grading, List.of(), explanation, spec.family(), spec.stability(),
                spec.reviewed(), derivedFrom);
    }

    private static JsonNode text(String value) {
        return MAPPER.getNodeFactory().textNode(value);
    }
}
