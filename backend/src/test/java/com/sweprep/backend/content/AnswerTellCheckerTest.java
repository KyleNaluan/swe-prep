package com.sweprep.backend.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.sweprep.backend.content.AnswerTellChecker.Finding;
import com.sweprep.backend.content.AnswerTellChecker.Tell;
import com.sweprep.backend.exercise.Comparison;
import com.sweprep.backend.exercise.Difficulty;
import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.Form;
import com.sweprep.backend.exercise.Grading;
import com.sweprep.backend.exercise.Option;
import com.sweprep.backend.exercise.Response;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The set-level answer-tell quality check (issue #60). It is exercised against
 * <em>fixtures</em> - a deliberately imbalanced check that must be flagged, and a balanced
 * one that must pass - so the guard is demonstrated on both sides on CI, independently of
 * whatever the real (private) content happens to look like. Real content is measured by a
 * separate, CI-skipped smoke test; keeping the demonstration on fixtures is what stops the
 * expected real-content failure from wedging CI into a permanently red build someone later
 * deletes.
 */
class AnswerTellCheckerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AnswerTellChecker checker = new AnswerTellChecker(mapper);

    @Test
    void flagsACheckWhoseCorrectOptionIsConspicuouslyLonger() {
        Exercise imbalanced = choice(
                "aiml-metrics-auc-like",
                Option.correct(
                        "AUC is the probability a random positive is ranked above a random "
                                + "negative, so it is threshold-independent and robust to imbalance"),
                Option.distractor("It is the area under the loss curve", "confuses ROC with loss"),
                Option.distractor("The accuracy at the 0.5 threshold", "collapses AUC to one point"),
                Option.distractor("The F1 score's harmonic mean", "conflates two metrics"));

        List<Finding> findings = checker.check(imbalanced);

        assertThat(findings).extracting(Finding::tell).contains(Tell.LENGTH_IMBALANCE);
        // The message is actionable: it names the check and the measured lengths, and says
        // to bring the distractors up - never to truncate the correct answer.
        Finding lengthFinding = findings.stream()
                .filter(f -> f.tell() == Tell.LENGTH_IMBALANCE)
                .findFirst()
                .orElseThrow();
        assertThat(lengthFinding.message())
                .contains("aiml-metrics-auc-like")
                .contains("characters")
                .containsIgnoringCase("do NOT")
                .containsIgnoringCase("distractors");
    }

    @Test
    void passesACheckWithRoughlyBalancedOptions() {
        Exercise balanced = choice(
                "balanced",
                Option.correct("A hash map gives O(1) average lookup by key"),
                Option.distractor("A sorted array gives O(log n) lookup by binary search", "m1"),
                Option.distractor("A linked list gives O(n) lookup by scanning nodes", "m2"),
                Option.distractor("A stack gives O(1) access only to its top element", "m3"));

        assertThat(checker.check(balanced)).isEmpty();
    }

    @Test
    void toleratesAnHonestlySlightlyLongerCorrectAnswer() {
        // A correct answer a little longer than its distractors is honest variation, not a
        // tell: the proportional threshold must not cry wolf on it.
        Exercise slightlyLonger = choice(
                "slightly-longer",
                Option.correct("O(1) average time per key lookup"),
                Option.distractor("O(log n) time per key lookup", "m1"),
                Option.distractor("O(n) time per key lookup", "m2"),
                Option.distractor("O(n log n) time per key lookup", "m3"));

        assertThat(checker.check(slightlyLonger))
                .extracting(Finding::tell)
                .doesNotContain(Tell.LENGTH_IMBALANCE);
    }

    @Test
    void flagsTheLoneQualifiedOptionAmongAbsolutes() {
        Exercise loneQualifier = choice(
                "lone-qualifier",
                Option.correct("A cache often improves read latency"),
                Option.distractor("A cache always improves read latency", "ignores cold misses"),
                Option.distractor("A cache never improves read latency", "denies the benefit"));

        assertThat(checker.check(loneQualifier))
                .extracting(Finding::tell)
                .contains(Tell.LONE_QUALIFIER);
    }

    @Test
    void isDomainAgnostic_flagsTheSameImbalanceForASqlCheck() {
        // The rule reads only option lengths, so a SQL fundamentals check and an AI/ML one
        // are flagged identically (domain-agnostic is binding).
        Exercise sql = choice(
                "sql-index-like",
                Option.correct(
                        "A B-tree index speeds range scans and equality lookups because its "
                                + "sorted structure lets the planner seek then walk in order"),
                Option.distractor("It makes every write faster", "confuses read and write cost"),
                Option.distractor("It removes the need for a WHERE clause", "misreads indexing"),
                Option.distractor("It stores the table twice on disk", "wrong mental model"));

        assertThat(checker.check(sql)).extracting(Finding::tell).contains(Tell.LENGTH_IMBALANCE);
    }

    @Test
    void flagsWhenTheCorrectOptionIsTheOnlyOneCarryingACausalConnective() {
        Exercise onlyKeyJustifies = choice(
                "connective-only-key",
                Option.correct("A hash map gives O(1) average lookup because keys hash directly "
                        + "to a bucket index"),
                Option.distractor("A sorted array gives O(log n) lookup by binary search", "m1"),
                Option.distractor("A linked list gives O(n) lookup by scanning nodes", "m2"),
                Option.distractor("A stack gives O(1) access only to its top element", "m3"));

        List<Finding> findings = checker.check(onlyKeyJustifies);

        assertThat(findings).extracting(Finding::tell).contains(Tell.CONNECTIVE_STYLE);
        Finding finding = findings.stream()
                .filter(f -> f.tell() == Tell.CONNECTIVE_STYLE)
                .findFirst()
                .orElseThrow();
        assertThat(finding.message())
                .contains("connective-only-key")
                .contains("because")
                .containsIgnoringCase("only one using");
    }

    @Test
    void flagsWithEqualSeverityWhenTheCorrectOptionIsTheOnlyOneLackingAConnective() {
        // The mirror case: every distractor justifies itself with "since" and the key
        // stands alone - exactly as exploitable as the reverse, and must be caught with
        // the same Tell (equal severity), not treated as a lesser case.
        Exercise onlyDistractorsJustify = choice(
                "connective-only-distractors",
                Option.correct("A hash map gives O(1) average lookup by key"),
                Option.distractor(
                        "A sorted array gives O(log n) lookup, since binary search halves the "
                                + "range each step",
                        "m1"),
                Option.distractor(
                        "A linked list gives O(n) lookup, since every node must be visited in "
                                + "turn",
                        "m2"),
                Option.distractor(
                        "A stack gives O(1) access only to its top element, since deeper "
                                + "elements are unreachable without popping",
                        "m3"));

        List<Finding> findings = checker.check(onlyDistractorsJustify);

        assertThat(findings).extracting(Finding::tell).contains(Tell.CONNECTIVE_STYLE);
        Finding finding = findings.stream()
                .filter(f -> f.tell() == Tell.CONNECTIVE_STYLE)
                .findFirst()
                .orElseThrow();
        assertThat(finding.message())
                .contains("connective-only-distractors")
                .contains("since")
                .containsIgnoringCase("only one NOT using");
    }

    @Test
    void passesWhenTheKeySharesConnectiveUseWithAtLeastOneDistractor() {
        // The key uses "because" and so does one distractor - the property is shared, not
        // one-sided, so it must not be flagged even though it is not universal.
        Exercise shared = choice(
                "connective-shared",
                Option.correct("A hash map gives O(1) average lookup because keys hash directly "
                        + "to a bucket index"),
                Option.distractor("A sorted array gives O(log n) lookup because it halves the "
                        + "search range each step", "m1"),
                Option.distractor("A linked list gives O(n) lookup by scanning nodes", "m2"),
                Option.distractor("A stack gives O(1) access only to its top element", "m3"));

        assertThat(checker.check(shared))
                .extracting(Finding::tell)
                .doesNotContain(Tell.CONNECTIVE_STYLE);
    }

    @Test
    void passesWhenNoOptionUsesAConnectiveAtAll() {
        Exercise noConnectives = choice(
                "connective-none",
                Option.correct("A hash map gives O(1) average lookup by key"),
                Option.distractor("A sorted array gives O(log n) lookup by binary search", "m1"),
                Option.distractor("A linked list gives O(n) lookup by scanning nodes", "m2"),
                Option.distractor("A stack gives O(1) access only to its top element", "m3"));

        assertThat(checker.check(noConnectives))
                .extracting(Finding::tell)
                .doesNotContain(Tell.CONNECTIVE_STYLE);
    }

    @Test
    void isBlindToConnectivesMergedIntoOneBucket_theExactLexemeMatters() {
        // A corpus audit found this exact shape leak through a merged "any connective"
        // check: the key uses "because" and every distractor uses "since" - a merged
        // bucket sees "a connective" on both sides and passes, but the exact lexeme
        // "since" is a clean 100%-distractors/0%-key split and must be caught.
        Exercise exactLexemeLeak = choice(
                "connective-exact-lexeme",
                Option.correct("Use F_beta with beta > 1 because it weights recall more heavily"),
                Option.distractor(
                        "Keep using F1, since the harmonic mean already balances precision and "
                                + "recall equally", "m1"),
                Option.distractor(
                        "Use F_beta with beta < 1, since a value below 1 reads as discounting "
                                + "precision", "m2"),
                Option.distractor(
                        "Switch to accuracy, since it treats every correct prediction the same",
                        "m3"));

        List<Finding> findings = checker.check(exactLexemeLeak);

        assertThat(findings).extracting(Finding::tell).contains(Tell.CONNECTIVE_STYLE);
    }

    @Test
    void ignoresNonChoiceContent() {
        // Only Choice + AnswerKey has a "correct option" and distractors to compare.
        Exercise code = choice("noop", Option.correct("only"), Option.distractor("x", "m"));
        assertThat(checker.check(code)).noneMatch(f -> f.exerciseId().equals("other"));
    }

    private static Exercise choice(String id, Option correct, Option... distractors) {
        Option[] all = new Option[distractors.length + 1];
        all[0] = correct;
        System.arraycopy(distractors, 0, all, 1, distractors.length);
        return new Exercise(
                id,
                "Title",
                "Statement.",
                "fundamentals",
                List.of("demo"),
                Difficulty.EASY,
                Form.REP,
                new Response.Choice(List.of(all)),
                new Grading.AnswerKey(TextNode.valueOf(correct.text()), Comparison.exact()),
                List.of());
    }
}
