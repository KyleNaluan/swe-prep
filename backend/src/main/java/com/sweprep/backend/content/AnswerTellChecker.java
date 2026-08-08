package com.sweprep.backend.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.Grading;
import com.sweprep.backend.exercise.Option;
import com.sweprep.backend.exercise.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A <b>content-quality</b> check for set-level "answer tells" in multiple-choice checks
 * (issue #60): properties of the option <em>set</em> that let a learner score without
 * reading the question. Unlike the loader's per-distractor gate (issue #42), which
 * inspects each distractor in isolation for a declared misconception, this looks across
 * the whole set - and, crucially, is <b>not</b> a load failure. A length imbalance is a
 * quality smell, not corruption; hard-failing content load over it would be wrong, so a
 * malformed file still fails at load while a merely lopsided one surfaces here as a
 * finding for an author to fix. It runs over real content in a CI-skipped smoke test, not
 * in {@code FileExerciseCatalog}.
 *
 * <p>The primary tell is <b>length</b>: in the first authored AI/ML batch the correct
 * answer was the longest option in all 12 checks, often by more than 2x, so "always pick
 * the longest" scored near-full marks. The threshold is deliberately <em>proportional</em>
 * rather than "the correct answer must never be longest": sometimes it legitimately is a
 * little longer, and a rule that cries wolf gets suppressed. The correct option is flagged
 * only when it exceeds {@link #DEFAULT_LENGTH_RATIO} times the mean length of the
 * distractors - targeting the systematic case while tolerating honest variation. The
 * number is tunable in one place (this constant, or the test constructor).
 *
 * <p>A cheap secondary tell is also flagged: the correct option being the <em>only</em>
 * qualified ("often", "typically", …) one among otherwise absolute distractors, another
 * shape a test-wise learner reads off without knowing the material. (The "only
 * grammatically-agreeing option" tell is left out: detecting grammatical agreement
 * reliably is not cheap, and a shaky heuristic there would cry wolf.)
 *
 * <p>Findings are advisory and their messages are actionable: the fix is always to bring
 * the distractors up to parity, never to truncate the correct answer into something
 * inaccurate.
 */
public final class AnswerTellChecker {

    /**
     * The correct option is flagged when it is longer than this multiple of the mean
     * distractor length. 1.3x tolerates honest variation (a correct answer that carries a
     * short "because …" clause) while catching the systematic 2x+ imbalance the batch
     * showed. Tune here.
     */
    public static final double DEFAULT_LENGTH_RATIO = 1.3;

    /**
     * Hedge words that make an option read as "qualified" rather than absolute. A single
     * qualified option among absolutes is itself a tell.
     */
    private static final Set<String> QUALIFIERS = Set.of(
            "often", "typically", "usually", "sometimes", "generally", "frequently",
            "occasionally", "may", "can", "most", "many", "rarely", "tend", "tends");

    private final ObjectMapper mapper;
    private final double lengthRatio;

    public AnswerTellChecker(ObjectMapper mapper) {
        this(mapper, DEFAULT_LENGTH_RATIO);
    }

    /** For tests: pin the proportional length threshold. */
    AnswerTellChecker(ObjectMapper mapper, double lengthRatio) {
        this.mapper = mapper;
        this.lengthRatio = lengthRatio;
    }

    /** Every answer-tell finding across {@code exercises}, empty when the set is clean. */
    public List<Finding> checkAll(Iterable<Exercise> exercises) {
        List<Finding> findings = new ArrayList<>();
        for (Exercise exercise : exercises) {
            findings.addAll(check(exercise));
        }
        return findings;
    }

    /** The answer-tell findings for one exercise (empty unless it is a Choice + AnswerKey). */
    public List<Finding> check(Exercise exercise) {
        if (!(exercise.response() instanceof Response.Choice choice)
                || !(exercise.grading() instanceof Grading.AnswerKey key)) {
            return List.of();
        }
        Option correct = ChoiceKeys.correctOption(choice, key, mapper).orElse(null);
        if (correct == null) {
            // No option matches the key; that is the loader's distractor gate to reject,
            // not a tell to measure here.
            return List.of();
        }
        List<Option> distractors = choice.options().stream()
                .filter(option -> option != correct)
                .toList();
        if (distractors.isEmpty()) {
            return List.of();
        }

        List<Finding> findings = new ArrayList<>();
        lengthImbalance(exercise.id(), correct, distractors).ifPresent(findings::add);
        loneQualifier(exercise.id(), correct, distractors).ifPresent(findings::add);
        return findings;
    }

    private java.util.Optional<Finding> lengthImbalance(
            String id, Option correct, List<Option> distractors) {
        int correctLen = length(correct);
        double meanOthers =
                distractors.stream().mapToInt(AnswerTellChecker::length).average().orElse(0);
        if (meanOthers <= 0 || correctLen <= lengthRatio * meanOthers) {
            return java.util.Optional.empty();
        }
        List<Integer> otherLens = distractors.stream().map(AnswerTellChecker::length).toList();
        String message = String.format(
                Locale.ROOT,
                "check '%s': the correct option is %d characters but the distractors are %s "
                        + "(mean %.1f) - a learner can score by picking the longest option "
                        + "without reading. Bring the distractors up to comparable length; do NOT "
                        + "truncate the correct answer into something inaccurate. "
                        + "(threshold: correct must be <= %.2fx the distractor mean)",
                id, correctLen, otherLens, meanOthers, lengthRatio);
        return java.util.Optional.of(new Finding(id, Tell.LENGTH_IMBALANCE, message));
    }

    private java.util.Optional<Finding> loneQualifier(
            String id, Option correct, List<Option> distractors) {
        if (!isQualified(correct)) {
            return java.util.Optional.empty();
        }
        boolean anyDistractorQualified = distractors.stream().anyMatch(AnswerTellChecker::isQualified);
        if (anyDistractorQualified) {
            return java.util.Optional.empty();
        }
        String message = String.format(
                Locale.ROOT,
                "check '%s': the correct option is the only qualified one (\"often\"/\"typically\"/"
                        + "…) among otherwise absolute distractors - a learner can pick the hedged "
                        + "option without reading. Qualify or de-qualify options for parity so the "
                        + "hedge is not a tell; do NOT change what the correct answer says.",
                id);
        return java.util.Optional.of(new Finding(id, Tell.LONE_QUALIFIER, message));
    }

    private static int length(Option option) {
        return option.text().strip().length();
    }

    private static boolean isQualified(Option option) {
        String text = option.text().toLowerCase(Locale.ROOT);
        for (String qualifier : QUALIFIERS) {
            // Word-boundary match so "many" does not fire on "Germany".
            if (containsWord(text, qualifier)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsWord(String text, String word) {
        int from = 0;
        while (true) {
            int at = text.indexOf(word, from);
            if (at < 0) {
                return false;
            }
            boolean leftOk = at == 0 || !Character.isLetter(text.charAt(at - 1));
            int end = at + word.length();
            boolean rightOk = end == text.length() || !Character.isLetter(text.charAt(end));
            if (leftOk && rightOk) {
                return true;
            }
            from = at + 1;
        }
    }

    /** The kind of answer tell a finding reports. */
    public enum Tell {
        LENGTH_IMBALANCE,
        LONE_QUALIFIER
    }

    /** One answer-tell finding: which check, which tell, and an actionable message. */
    public record Finding(String exerciseId, Tell tell, String message) {}
}
