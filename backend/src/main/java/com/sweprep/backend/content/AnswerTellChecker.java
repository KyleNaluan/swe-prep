package com.sweprep.backend.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.Grading;
import com.sweprep.backend.exercise.Option;
import com.sweprep.backend.exercise.Response;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.ToIntFunction;

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
 * <p>Issue #67 extended this same axis two ways, after a corpus audit found "pick the
 * shortest option by word count" scoring 41.2% (AI/ML metrics corpus) and 36.0% (Core
 * batch 1 corpus) against a 25% four-option baseline - the single strongest exploit in
 * either batch - while character-based measures on the same content looked unremarkable:
 * the word-count tell was hiding entirely behind a passing character check. First, the
 * ratio bar is now measured against <b>word count as well as characters</b>, and is
 * <b>two-sided</b>: a ratio of 0.57 (correct much shorter than its distractors) is exactly
 * as exploitable as 1.75, and was previously invisible since the original bar only caught
 * "too long." Second, a key can sit comfortably inside that ratio bar and still be the
 * <b>uniquely shortest or uniquely longest</b> option; {@link #rankExtreme} flags that,
 * with equal severity in either direction. Rank uniqueness is deliberately checked on word
 * count only, not characters: character count is close to continuous prose, so two
 * independently-written sentences almost never land on the exact same value, and a corpus
 * measurement found raw character-count rank flagging a majority of already-balanced,
 * hand-reviewed content on that noise alone (including this class's own "roughly balanced"
 * fixture) - the exact false-alarm failure mode issue #69 warns against. Word count is
 * coarse enough that ties are common and a unique extreme is a real signal, which matches
 * what the production exploit actually measured. As with connective style, the passing
 * condition is symmetric and never demands uniformity: the fix is to bring the correct
 * option (or the distractors) to comparable length, and a unique extreme is the defect -
 * not "every option is a different length," which collapsing them all to one length would
 * only trade for a different, equally exploitable tell. The regression fixture is two real
 * items from swe-prep-content history at their pre-fix commit, not synthetic examples -
 * {@code http-status-classes} (key 13 words against distractors of 21/23/24) and {@code
 * cache-no-cache-vs-no-store} (key 16 words against 24/27/29) - both of which shipped
 * through a red-team, an adjudication pass and an author self-check before this check
 * existed.
 *
 * <p>A cheap secondary tell is also flagged: the correct option being the <em>only</em>
 * qualified ("often", "typically", …) one among otherwise absolute distractors, another
 * shape a test-wise learner reads off without knowing the material. (The "only
 * grammatically-agreeing option" tell is left out: detecting grammatical agreement
 * reliably is not cheap, and a shaky heuristic there would cry wolf.)
 *
 * <p>A third, set-level axis (issue #65) is <b>connective style</b>: a check is flagged
 * when the correct option is the only one carrying a causal connective ("since",
 * "because", …) and, with equal severity, when it is the only one <em>lacking</em> one -
 * "pick the option that does not justify itself" is exactly as exploitable as "pick the
 * option that does." This was found scoring 4/4 with zero domain knowledge on a real
 * authored batch (swe-prep-content PR #5) before a red-team caught it by hand. The
 * passing condition is symmetric: the key must merely <em>share</em> the property with at
 * least one distractor, never that the property point a particular way - see
 * {@link #connectiveStyle}.
 *
 * <p>Findings are advisory and their messages are actionable: the fix is always to bring
 * the distractors (or, for connective style, either side) up to parity, never to
 * truncate the correct answer into something inaccurate or to simply invert which side
 * carries the tell - that inversion, not a missed fix, is this repo's dominant recurring
 * failure on these checks.
 */
public final class AnswerTellChecker {

    /**
     * The correct option is flagged when it is longer than this multiple of the mean
     * distractor length, or shorter than its reciprocal (issue #67 made the bar two-sided:
     * 1/1.3 is exactly as much a failure as 1.3, not just the "too long" direction). 1.3x
     * tolerates honest variation (a correct answer that carries a short "because …" clause)
     * while catching the systematic 2x+ imbalance the first AI/ML batch showed. Tune here.
     */
    public static final double DEFAULT_LENGTH_RATIO = 1.3;

    /** One length dimension: how to measure it, and the words used to name it in a finding. */
    private record LengthMetric(String unit, String name, ToIntFunction<Option> measure) {}

    private static final LengthMetric CHARACTER_METRIC =
            new LengthMetric("characters", "character count", AnswerTellChecker::length);
    private static final LengthMetric WORD_METRIC =
            new LengthMetric("words", "word count", AnswerTellChecker::wordCount);

    /**
     * The dimensions checked for ratio parity (issue #67), in evaluation order. Rank
     * uniqueness ({@link #rankExtreme}) deliberately checks only {@link #WORD_METRIC} - see
     * the class javadoc for why character count is too noisy a signal for rank.
     */
    private static final List<LengthMetric> RATIO_METRICS = List.of(CHARACTER_METRIC, WORD_METRIC);

    /**
     * Hedge words that make an option read as "qualified" rather than absolute. A single
     * qualified option among absolutes is itself a tell.
     */
    private static final Set<String> QUALIFIERS = Set.of(
            "often", "typically", "usually", "sometimes", "generally", "frequently",
            "occasionally", "may", "can", "most", "many", "rarely", "tend", "tends");

    /**
     * Causal connectives, in one place so a newly discovered near-neighbour is a one-line
     * addition (issue #65's fourth acceptance criterion). Checked one lexeme at a time
     * (see {@link #connectiveStyle}), <b>never</b> merged into one "does it have some
     * connective" bucket: a corpus audit measured that a merged-bucket version of this
     * check passed on a batch where every key said "because" and every distractor said
     * "since" - both sides read as "has a connective" to the merged bucket, hiding the
     * exact-lexeme imbalance that a learner could actually exploit. An ordered list, not a
     * {@code Set}, so the lexeme scanned first (and therefore reported first, when more
     * than one trips) is deterministic.
     *
     * <p><b>{@code as} was dropped (issue #69)</b> after it was measured to be
     * responsible for 3 of this axis's 4 flags on the 29 real four-option items in
     * production content - and every one of those 3 a false alarm, not just the "at
     * least two" #69 confirmed by hand. {@code as} is the most polysemous word ever in
     * this list: a full scan of every {@code as} occurrence across that same corpus
     * found it firing on comparison ("as well as", "just as directly as"), labelling
     * ("flags it as positive", "treated as stale", "another name for … as"), and
     * temporal senses ("as the world changes") - never once on a genuine causal use.
     * With zero true positives measured anywhere in the corpus to weigh against three
     * confirmed false ones, a causal-context heuristic (e.g. only count sentence-initial
     * "As " or ", as ") had nothing left to preserve: the corpus's temporal and
     * comparison uses ("as the world changes", ", as if the two independent stores
     * could be…") sit right where such a heuristic would still fire, so it would not
     * even have closed the gap cheaply. The one genuine catch in the same measurement
     * was a "because" tell (every distractor opened with "Because", the key did not) -
     * untouched by this change, since it never depended on {@code as}. Keeping a lexeme
     * whose flags a reviewer learns to skim past - #65's own stated rationale for this
     * axis - trains them to skim past the real ones too.
     */
    private static final List<String> CONNECTIVES =
            List.of("since", "because", "so that");

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
        connectiveStyle(exercise.id(), correct, distractors).ifPresent(findings::add);
        return findings;
    }

    /**
     * The length-axis finding (issue #60, extended by #67), or empty when the key is
     * neither a ratio outlier on either length metric nor a unique rank extreme on word
     * count. Ratio is checked first (both metrics, each direction) since it is the more
     * systemic signal; rank is checked last since it catches only what ratio's tolerance
     * band lets through. At most one finding, matching {@link #loneQualifier}/{@link
     * #connectiveStyle}.
     */
    private java.util.Optional<Finding> lengthImbalance(
            String id, Option correct, List<Option> distractors) {
        for (LengthMetric metric : RATIO_METRICS) {
            java.util.Optional<Finding> ratio = ratioImbalance(id, correct, distractors, metric);
            if (ratio.isPresent()) {
                return ratio;
            }
        }
        return rankExtreme(id, correct, distractors, WORD_METRIC);
    }

    /** The two-sided ratio check for one length metric: too long, or (issue #67) too short. */
    private java.util.Optional<Finding> ratioImbalance(
            String id, Option correct, List<Option> distractors, LengthMetric metric) {
        int correctValue = metric.measure().applyAsInt(correct);
        List<Integer> otherValues =
                distractors.stream().map(metric.measure()::applyAsInt).toList();
        double mean = otherValues.stream().mapToInt(Integer::intValue).average().orElse(0);
        if (mean <= 0) {
            return java.util.Optional.empty();
        }
        if (correctValue > lengthRatio * mean) {
            return java.util.Optional.of(
                    ratioFinding(id, metric, "longest", correctValue, otherValues, mean));
        }
        if (correctValue < mean / lengthRatio) {
            return java.util.Optional.of(
                    ratioFinding(id, metric, "shortest", correctValue, otherValues, mean));
        }
        return java.util.Optional.empty();
    }

    private Finding ratioFinding(
            String id,
            LengthMetric metric,
            String direction,
            int correctValue,
            List<Integer> otherValues,
            double mean) {
        String action =
                direction.equals("longest")
                        ? "Bring the distractors up to comparable length; do NOT truncate the "
                                + "correct answer into something inaccurate."
                        : "Bring the correct option up to comparable length, or trim the "
                                + "distractors down to parity; do NOT simply invert which side "
                                + "reads short.";
        String message = String.format(
                Locale.ROOT,
                "check '%s': the correct option is %d %s but the distractors are %s (mean %.1f "
                        + "%s) - a learner can score by picking the %s option by %s without "
                        + "reading. %s (threshold: correct must be between %.2fx and %.2fx the "
                        + "distractor mean)",
                id,
                correctValue,
                metric.unit(),
                otherValues,
                mean,
                metric.unit(),
                direction,
                metric.name(),
                action,
                1 / lengthRatio,
                lengthRatio);
        return new Finding(id, Tell.LENGTH_IMBALANCE, message);
    }

    /**
     * The rank check (issue #67): the correct option flagged for being the <em>only</em>
     * option at the minimum or maximum of {@code metric}, regardless of how close the ratio
     * bar would otherwise call it. A tie at the extreme - including the degenerate case
     * where every option is the same length - passes: the property required is "shares the
     * extreme with at least one distractor," never "every option is identical."
     */
    private java.util.Optional<Finding> rankExtreme(
            String id, Option correct, List<Option> distractors, LengthMetric metric) {
        int correctValue = metric.measure().applyAsInt(correct);
        List<Integer> otherValues = distractors.stream().map(metric.measure()::applyAsInt).toList();
        List<Integer> allValues = new ArrayList<>(otherValues);
        allValues.add(correctValue);
        int min = Collections.min(allValues);
        int max = Collections.max(allValues);
        if (min == max) {
            return java.util.Optional.empty();
        }
        long atMin = allValues.stream().filter(v -> v == min).count();
        long atMax = allValues.stream().filter(v -> v == max).count();
        String direction;
        if (correctValue == min && atMin == 1) {
            direction = "shortest";
        } else if (correctValue == max && atMax == 1) {
            direction = "longest";
        } else {
            return java.util.Optional.empty();
        }
        String message = String.format(
                Locale.ROOT,
                "check '%s': the correct option is the uniquely %s option by %s (%d %s; the "
                        + "distractors are %s) - a learner can score by picking the %s option "
                        + "without reading, even though it sits inside the ratio bar. The "
                        + "correct option must not be a unique extreme by this metric - share "
                        + "the extreme with at least one distractor, or move it toward the "
                        + "middle of the pack; do NOT collapse every option to the same length "
                        + "instead.",
                id, direction, metric.name(), correctValue, metric.unit(), otherValues, direction);
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

    /**
     * The connective-style finding (issue #65), or empty when the key shares each
     * lexeme's presence/absence with at least one distractor. Scanned one connective at a
     * time in {@link #CONNECTIVES} order; the first lexeme that isolates the key - either
     * direction - is reported, so a check with more than one imbalanced lexeme still
     * yields exactly one finding (matching {@link #lengthImbalance}/{@link
     * #loneQualifier}, each of which also contributes at most one finding per exercise).
     */
    private java.util.Optional<Finding> connectiveStyle(
            String id, Option correct, List<Option> distractors) {
        String correctText = correct.text().toLowerCase(Locale.ROOT);
        for (String connective : CONNECTIVES) {
            boolean correctHas = containsWord(correctText, connective);
            long distractorsWithIt = distractors.stream()
                    .filter(option -> containsWord(option.text().toLowerCase(Locale.ROOT), connective))
                    .count();
            if (correctHas && distractorsWithIt == 0) {
                String message = String.format(
                        Locale.ROOT,
                        "check '%s': the correct option is the only one using the causal connective "
                                + "'%s' - a learner can pick the option that justifies itself without "
                                + "reading. Add '%s' (or a similar connective) to at least one "
                                + "distractor, or remove it from the correct answer, so the connective "
                                + "is not a tell; do NOT simply move it onto the distractors instead.",
                        id, connective, connective);
                return java.util.Optional.of(new Finding(id, Tell.CONNECTIVE_STYLE, message));
            }
            if (!correctHas && distractorsWithIt == distractors.size()) {
                String message = String.format(
                        Locale.ROOT,
                        "check '%s': the correct option is the only one NOT using the connective "
                                + "'%s' - every distractor uses it and the correct answer stands alone, "
                                + "so a learner can pick the odd one out without reading (the key may "
                                + "still carry a different connective). Add '%s' to the correct answer, "
                                + "or remove it from at least one distractor, so the connective is not a "
                                + "tell.",
                        id, connective, connective);
                return java.util.Optional.of(new Finding(id, Tell.CONNECTIVE_STYLE, message));
            }
        }
        return java.util.Optional.empty();
    }

    private static int length(Option option) {
        return option.text().strip().length();
    }

    private static int wordCount(Option option) {
        String text = option.text().strip();
        return text.isEmpty() ? 0 : text.split("\\s+").length;
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

    /** Word-boundary substring match; {@code word} may be a single token or a phrase. */
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
        LONE_QUALIFIER,
        CONNECTIVE_STYLE
    }

    /** One answer-tell finding: which check, which tell, and an actionable message. */
    public record Finding(String exerciseId, Tell tell, String message) {}
}
