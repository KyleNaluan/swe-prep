package com.sweprep.backend.reps;

import static org.assertj.core.api.Assertions.assertThat;

import com.sweprep.backend.attempt.SubmissionRepository.FailedResponse;
import com.sweprep.backend.exercise.Comparison;
import com.sweprep.backend.exercise.Difficulty;
import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.Family;
import com.sweprep.backend.exercise.Form;
import com.sweprep.backend.exercise.Grading;
import com.sweprep.backend.exercise.Response;
import com.sweprep.backend.exercise.Stability;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The warm-up selector is the substance of issue #18 that has no runner, no database
 * and no scheduler in it, so it is proven here in isolation: reps only, active families
 * only, derived reps gated on their problem, and - the sharp one - a set that is never
 * blocked practice even when the eligible queue is stacked with one topic (design
 * revision t3, section 4.2).
 */
class WarmupSelectorTest {

    private static final Set<Family> ALL = EnumSet.allOf(Family.class);
    private static final Set<String> NOTHING_ATTEMPTED = Set.of();

    @Test
    void takesOnlyReps() {
        WarmupSelector selector = new WarmupSelector(8, 2);
        List<Exercise> catalog = List.of(
                rep("r1", "arrays", List.of(Family.CORE), null),
                challenge("c1", "arrays"),
                rep("r2", "graphs", List.of(Family.CORE), null));

        List<Exercise> set = selector.select(catalog, ALL, NOTHING_ATTEMPTED);

        assertThat(set).extracting(Exercise::id).containsExactly("r1", "r2");
    }

    @Test
    void excludesSelfCheckRepsSoTheRequiredCoreStaysRecognitionAndFast() {
        // A self-graded "explain in your own words" item is production, not recognition, and
        // its produce-then-reveal flow does not belong in the ~4-minute core (issue #41). Even
        // authored as a REP it must never leak into the warm-up; it lives in the optional tiers.
        WarmupSelector selector = new WarmupSelector(8, 2);
        List<Exercise> catalog = List.of(
                rep("r1", "arrays", List.of(Family.CORE), null),
                selfCheckRep("explain1", "concepts"),
                rep("r2", "graphs", List.of(Family.CORE), null));

        List<Exercise> set = selector.select(catalog, ALL, NOTHING_ATTEMPTED);

        assertThat(set).extracting(Exercise::id).containsExactly("r1", "r2");
    }

    @Test
    void capsTheSetAtItsConfiguredSize() {
        WarmupSelector selector = new WarmupSelector(3, 2);
        List<Exercise> catalog = new ArrayList<>();
        // Alternating topics so the interleave never has to fall back, isolating the size cap.
        for (int i = 0; i < 10; i++) {
            catalog.add(rep("r" + i, i % 2 == 0 ? "a" : "b", List.of(Family.CORE), null));
        }

        assertThat(selector.select(catalog, ALL, NOTHING_ATTEMPTED)).hasSize(3);
    }

    @Test
    void suppressesInactiveFamiliesButKeepsAlwaysOnAndUntagged() {
        WarmupSelector selector = new WarmupSelector(8, 2);
        List<Exercise> catalog = List.of(
                rep("core", "a", List.of(Family.CORE), null),
                rep("professional", "b", List.of(Family.PROFESSIONAL), null),
                rep("backend", "c", List.of(Family.BACKEND), null),
                rep("data", "d", List.of(Family.DATA), null),
                rep("untagged", "e", List.of(), null));

        // Only BACKEND is active; CORE/PROFESSIONAL are always on, untagged is substrate,
        // and DATA is suppressed from the core.
        List<Exercise> set = selector.select(catalog, EnumSet.of(Family.BACKEND), NOTHING_ATTEMPTED);

        assertThat(set).extracting(Exercise::id)
                .containsExactlyInAnyOrder("core", "professional", "backend", "untagged")
                .doesNotContain("data");
    }

    @Test
    void gatesDerivedRepsOnHavingAttemptedTheProblemButServesPatternIdCold() {
        WarmupSelector selector = new WarmupSelector(8, 2);
        List<Exercise> catalog = List.of(
                rep("pattern-id", "a", List.of(Family.CORE), null), // cold: no derivedFrom
                rep("complexity", "b", List.of(Family.CORE), "two-sum"), // gated on two-sum
                rep("spot-bug", "c", List.of(Family.CORE), "reverse")); // gated on reverse

        // Nothing attempted: only the cold pattern-id rep is served.
        assertThat(selector.select(catalog, ALL, NOTHING_ATTEMPTED))
                .extracting(Exercise::id)
                .containsExactly("pattern-id");

        // Once two-sum is attempted, its derived rep unlocks; reverse's stays gated.
        assertThat(selector.select(catalog, ALL, Set.of("two-sum")))
                .extracting(Exercise::id)
                .containsExactlyInAnyOrder("pattern-id", "complexity")
                .doesNotContain("spot-bug");
    }

    @Test
    void interleavesAQueueStackedWithOneTopic() {
        WarmupSelector selector = new WarmupSelector(8, 2);
        // A deliberately stacked queue: five two-pointer reps then three sliding-window,
        // all the same domain. A naive "pull everything due" would emit tp,tp,tp,tp,tp,...
        List<Exercise> stacked = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            stacked.add(rep("tp" + i, "two-pointer", List.of(Family.CORE), null));
        }
        for (int i = 0; i < 3; i++) {
            stacked.add(rep("sw" + i, "sliding-window", List.of(Family.CORE), null));
        }

        List<Exercise> set = selector.select(stacked, ALL, NOTHING_ATTEMPTED);

        assertThat(set).hasSize(8);
        // Every rep is the algorithms domain, so a domain run is unavoidable; the point is
        // that the topics come out interleaved rather than five two-pointer reps in a row.
        assertNoTopicRunLongerThan(set, 2);
    }

    @Test
    void capsConsecutiveSameDomainWhenDomainsVary() {
        WarmupSelector selector = new WarmupSelector(8, 2);
        // Two domains, every rep a distinct topic so the topic cap never forces anything -
        // isolating the domain cap. A queue stacked with one domain must still come mixed.
        List<Exercise> catalog = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            catalog.add(rep("algo" + i, "algo-topic-" + i, "algorithms", List.of(Family.CORE), null));
        }
        for (int i = 0; i < 3; i++) {
            catalog.add(rep("sql" + i, "sql-topic-" + i, "sql", List.of(Family.CORE), null));
        }

        List<Exercise> set = selector.select(catalog, ALL, NOTHING_ATTEMPTED);

        assertThat(set).hasSize(8);
        assertRunWithinCap(set, 2, Exercise::domain, "domain");
    }

    @Test
    void honoursTheTopicCapEvenWhenEveryRepSharesOneDomain() {
        WarmupSelector selector = new WarmupSelector(8, 2);
        // All algorithms domain, so the domain cap can never be satisfied - the topic cap
        // must still be honoured rather than poisoned by the uniform domain.
        List<Exercise> catalog = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            catalog.add(rep("tp" + i, "two-pointer", List.of(Family.CORE), null));
        }
        for (int i = 0; i < 4; i++) {
            catalog.add(rep("sw" + i, "sliding-window", List.of(Family.CORE), null));
        }

        List<Exercise> set = selector.select(catalog, ALL, NOTHING_ATTEMPTED);

        assertThat(set).hasSize(8);
        assertThat(set).allSatisfy(e -> assertThat(e.domain()).isEqualTo("algorithms"));
        assertNoTopicRunLongerThan(set, 2);
    }

    @Test
    void prefersConfusableWithinDomainJuxtaposition() {
        WarmupSelector selector = new WarmupSelector(4, 2);
        // Four distinct patterns, one rep each, all the same domain and equal counts, so
        // the anti-stranding count key never discriminates - only confusability can. The
        // input order is deliberately NOT confusable-adjacent: two-pointer, then
        // binary-search, then its real confusion partner sliding-window, then hashing.
        Exercise tp = patternRep("tp", "two-pointer", "Two pointers");
        Exercise bs = patternRep("bs", "binary-search", "Binary search");
        Exercise sw = patternRep("sw", "sliding-window", "Sliding window");
        Exercise ha = patternRep("ha", "hashing", "Hash set");
        List<Exercise> catalog = List.of(tp, bs, sw, ha);
        // A learner once picked the sliding-window label on the two-pointer rep: the two
        // patterns are confusable, so the selector should juxtapose them.
        ConfusionPairs pairs =
                ConfusionPairs.derive(List.of(new FailedResponse("tp", "Sliding window")), catalog);

        List<Exercise> set = selector.select(catalog, ALL, NOTHING_ATTEMPTED, pairs);

        assertThat(set).hasSize(4);
        // Two-pointer leads, then its confusable partner sliding-window is pulled ahead of
        // binary-search, which sits earlier in the queue. A shuffle that merely avoids
        // repeats would have placed binary-search at index 1 - this is juxtaposition, the
        // discrimination win of section 4.2, not de-duplication.
        assertThat(set.subList(0, 2)).extracting(Exercise::id).containsExactly("tp", "sw");
        assertNoTopicRunLongerThan(set, 2);
    }

    @Test
    void treatsCrossDomainConfusableAsSpacingNotInterleaving() {
        WarmupSelector selector = new WarmupSelector(3, 2);
        // A confusion pair that spans domains: on the algorithms two-pointer rep a learner
        // picked the sql left-join label. It is a real topic-level confusion but crosses a
        // domain boundary, which the audit says is spacing, not discrimination training.
        Exercise tp = patternRep("tp", "two-pointer", "Two pointers", "algorithms");
        Exercise grp = patternRep("grp", "grouping", "Group by", "sql");
        Exercise lj = patternRep("lj", "left-join", "Left join", "sql");
        List<Exercise> catalog = List.of(tp, grp, lj);
        ConfusionPairs pairs =
                ConfusionPairs.derive(List.of(new FailedResponse("tp", "Left join")), catalog);

        List<Exercise> set = selector.select(catalog, ALL, NOTHING_ATTEMPTED, pairs);

        // Two-pointer leads. Between the two sql reps, left-join is confusable with
        // two-pointer but across a domain boundary, so it earns no juxtaposition bonus; the
        // earlier grouping rep is placed instead. Were cross-domain confusion rewarded,
        // left-join would have jumped ahead.
        assertThat(set).extracting(Exercise::id).containsExactly("tp", "grp", "lj");
    }

    @Test
    void anEmptyConfusionRelationLeavesTheStackedSetInterleavedAsBefore() {
        WarmupSelector selector = new WarmupSelector(8, 2);
        // Cold history - every install's current state: no confusion recorded, so the
        // confusable preference never fires and the selector falls back to its
        // family/gating/cap interleaving alone. The stacked queue must still come mixed.
        List<Exercise> stacked = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            stacked.add(rep("tp" + i, "two-pointer", List.of(Family.CORE), null));
        }
        for (int i = 0; i < 3; i++) {
            stacked.add(rep("sw" + i, "sliding-window", List.of(Family.CORE), null));
        }

        List<Exercise> set = selector.select(stacked, ALL, NOTHING_ATTEMPTED, ConfusionPairs.empty());

        assertThat(set).hasSize(8);
        assertNoTopicRunLongerThan(set, 2);
    }

    @Test
    void degradesGracefullyWhenNoInterleavingIsPossible() {
        WarmupSelector selector = new WarmupSelector(4, 2);
        // Every rep is the same topic and domain: there is nothing to interleave, so the
        // selector still returns a full set rather than refusing (a run is unavoidable).
        List<Exercise> catalog = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            catalog.add(rep("r" + i, "arrays", List.of(Family.CORE), null));
        }

        assertThat(selector.select(catalog, ALL, NOTHING_ATTEMPTED)).hasSize(4);
    }

    private static void assertNoTopicRunLongerThan(List<Exercise> set, int cap) {
        assertRunWithinCap(set, cap, e -> e.topics().get(0), "topic");
    }

    private static void assertRunWithinCap(
            List<Exercise> set,
            int cap,
            java.util.function.Function<Exercise, String> key,
            String label) {
        int run = 1;
        for (int i = 1; i < set.size(); i++) {
            run = key.apply(set.get(i)).equals(key.apply(set.get(i - 1))) ? run + 1 : 1;
            assertThat(run)
                    .withFailMessage(
                            "run of %d consecutive reps sharing a %s at index %d: %s",
                            run, label, i, set.stream().map(key).toList())
                    .isLessThanOrEqualTo(cap);
        }
    }

    private static Exercise rep(String id, String topic, List<Family> family, String derivedFrom) {
        return rep(id, topic, "algorithms", family, derivedFrom);
    }

    private static Exercise rep(
            String id, String topic, String domain, List<Family> family, String derivedFrom) {
        return new Exercise(
                id,
                id,
                "statement",
                domain,
                List.of(topic),
                Difficulty.EASY,
                Form.REP,
                new Response.Choice(List.of("A", "B")),
                new Grading.AnswerKey(TextNode.valueOf("A"), Comparison.exact()),
                List.of(),
                null,
                family,
                Stability.STABLE,
                null,
                derivedFrom);
    }

    private static Exercise patternRep(String id, String topic, String correctLabel) {
        return patternRep(id, topic, correctLabel, "algorithms");
    }

    /**
     * A pattern-identification rep whose options are pattern names and whose answer key is
     * one of them, so a wrong answer resolves to another rep's topic - the shape {@link
     * ConfusionPairs#derive} reads. The option list is a superset covering every label the
     * confusability tests use.
     */
    private static Exercise patternRep(String id, String topic, String correctLabel, String domain) {
        return new Exercise(
                id,
                id,
                "Which pattern fits?",
                domain,
                List.of(topic),
                Difficulty.EASY,
                Form.REP,
                new Response.Choice(
                        List.of(
                                "Two pointers", "Sliding window", "Binary search", "Hash set",
                                "Group by", "Left join")),
                new Grading.AnswerKey(TextNode.valueOf(correctLabel), Comparison.exact()),
                List.of(),
                null,
                List.of(Family.CORE),
                Stability.STABLE,
                null,
                null);
    }

    /** A self-graded explain rep (FreeText + SelfCheck) - the kind the warm-up must exclude. */
    private static Exercise selfCheckRep(String id, String topic) {
        return new Exercise(
                id,
                id,
                "Explain it in your own words.",
                "fundamentals",
                List.of(topic),
                Difficulty.EASY,
                Form.REP,
                new Response.FreeText(),
                new Grading.SelfCheck("the model answer"),
                List.of(),
                null,
                List.of(Family.CORE),
                Stability.STABLE,
                null,
                null);
    }

    private static Exercise challenge(String id, String topic) {
        return new Exercise(
                id,
                id,
                "statement",
                "algorithms",
                List.of(topic),
                Difficulty.EASY,
                Form.CHALLENGE,
                new Response.Choice(List.of("A", "B")),
                new Grading.AnswerKey(TextNode.valueOf("A"), Comparison.exact()),
                List.of());
    }
}
