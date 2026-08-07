package com.sweprep.backend.reps;

import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.Family;
import com.sweprep.backend.exercise.Form;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Builds one warm-up set: the ~8-rep, ~4-minute daily core (issues #3, #9, #18). It is
 * pure and deterministic - given the same catalog, active families and attempted
 * problems it returns the same ordered set - so the whole of its behaviour is unit
 * testable without a database or a scheduler. The spaced-repetition scheduler that
 * decides <em>which</em> reps are due is a later ticket (#38/#39); what this selector
 * owes now is narrower and load-bearing: the set it hands back is drawn from the right
 * content and is never blocked practice.
 *
 * <p>Three rules, all from the design revision (sections 2.2 and 4.2):
 *
 * <ol>
 *   <li><b>Only reps.</b> Only {@link Form#REP} exercises are warm-up material; a
 *       challenge is a full sitting, not a warm-up.
 *   <li><b>Active families only.</b> The required core draws only from the active
 *       families plus the always-on {@link Family#CORE} and {@link Family#PROFESSIONAL}
 *       (section 2.2). Untagged content is treated as always-eligible substrate, so
 *       existing reps that predate the family taxonomy are never silently dropped.
 *   <li><b>Gating.</b> A derived rep (complexity, fill-in-the-blank, predict-output,
 *       spot-the-bug) is served only once its underlying problem has been attempted;
 *       practising it cold is guessing, not practice. A pattern-identification rep
 *       carries no {@link Exercise#derivedFrom()} and so is available cold - the point
 *       of it (issue #18).
 * </ol>
 *
 * <p>Then the eligible reps are <b>interleaved, not merely due-ordered</b> (section
 * 4.2): a naive "take everything due" degrades into blocked practice, which trains
 * nothing. No more than {@link #maxConsecutiveSame} reps in a row may share a primary
 * topic (the algorithmic pattern) or a domain, so a queue stacked with one topic still
 * comes out mixed. The topic cap is the load-bearing one - juxtaposing confusable
 * within-domain patterns is where discrimination is trained, and cross-domain mixing is
 * mere spacing (section 4.2) - so topic balance wins over domain balance when the two
 * conflict, and the topic cap is honoured even when every rep shares one domain (an
 * all-algorithms warm-up must not be forced into topic runs just because its domain
 * cannot vary). Interleaving is done <b>most-common-topic-first</b>, the reorganize
 * standard: greedily switching topics too eagerly strands the majority topic in a run
 * at the tail, so at each step the eligible topic with the most reps still waiting is
 * placed (unless it would breach the cap), which keeps runs within the cap whenever any
 * arrangement can. Preferring <em>confusable</em> pairs specifically, and true due-date
 * ordering, are #39.
 */
public final class WarmupSelector {

    /** Families active for every user regardless of the family filter (section 2.2). */
    private static final Set<Family> ALWAYS_ON = EnumSet.of(Family.CORE, Family.PROFESSIONAL);

    private final int size;
    private final int maxConsecutiveSame;

    public WarmupSelector(int size, int maxConsecutiveSame) {
        if (size < 1) {
            throw new IllegalArgumentException("warm-up size must be positive: " + size);
        }
        if (maxConsecutiveSame < 1) {
            throw new IllegalArgumentException(
                    "max consecutive same must be positive: " + maxConsecutiveSame);
        }
        this.size = size;
        this.maxConsecutiveSame = maxConsecutiveSame;
    }

    /**
     * The ordered warm-up set, at most {@link #size} reps and possibly fewer when the
     * eligible pool is smaller.
     *
     * @param catalog           every loaded exercise (reps and challenges alike)
     * @param activeFamilies    the families the user has turned on, excluding the
     *                          always-on ones; may be empty
     * @param attemptedProblems the ids of problems this user has attempted, used to gate
     *                          derived reps
     */
    public List<Exercise> select(
            List<Exercise> catalog, Set<Family> activeFamilies, Set<String> attemptedProblems) {
        List<Exercise> eligible = catalog.stream()
                .filter(exercise -> exercise.form() == Form.REP)
                .filter(exercise -> familyEligible(exercise, activeFamilies))
                .filter(exercise -> gatingEligible(exercise, attemptedProblems))
                .toList();
        return interleave(eligible);
    }

    /**
     * A rep is family-eligible when it is untagged (always-on substrate) or serves at
     * least one active or always-on family. Deactivating a family only stops it seeding
     * the core; that filter (its user setting) is a separate ticket (#40).
     */
    private static boolean familyEligible(Exercise exercise, Set<Family> activeFamilies) {
        List<Family> families = exercise.family();
        if (families.isEmpty()) {
            return true;
        }
        return families.stream()
                .anyMatch(family -> ALWAYS_ON.contains(family) || activeFamilies.contains(family));
    }

    /** A rep with no {@code derivedFrom} is cold-available; otherwise its problem must be attempted. */
    private static boolean gatingEligible(Exercise exercise, Set<String> attemptedProblems) {
        String problem = exercise.derivedFrom();
        return problem == null || attemptedProblems.contains(problem);
    }

    private List<Exercise> interleave(List<Exercise> eligible) {
        List<Exercise> remaining = new ArrayList<>(eligible);
        List<Exercise> result = new ArrayList<>();
        while (result.size() < size && !remaining.isEmpty()) {
            result.add(remaining.remove(pickIndex(result, remaining)));
        }
        return result;
    }

    /**
     * The index of the next rep to place, scored on three keys in priority order: it does
     * not breach the topic cap (the load-bearing constraint); its topic has the most reps
     * still waiting (most-common-first, so the majority topic is never stranded into a
     * tail run); and it does not breach the domain cap (a secondary tiebreak, since
     * cross-domain mixing is only spacing). Ties keep the earliest candidate, so the
     * result is deterministic. When every remaining topic is capped out - only one topic
     * is left - a run is unavoidable and the highest-count candidate is taken anyway.
     */
    private int pickIndex(List<Exercise> chosen, List<Exercise> remaining) {
        Map<String, Long> topicCounts = topicCounts(remaining);
        int best = -1;
        boolean bestTopicOk = false;
        long bestTopicCount = -1;
        boolean bestDomainOk = false;
        for (int i = 0; i < remaining.size(); i++) {
            Exercise candidate = remaining.get(i);
            boolean topicOk = !runWouldExceed(chosen, candidate, WarmupSelector::primaryTopic);
            long topicCount = topicCounts.get(topicKey(candidate));
            boolean domainOk = !runWouldExceed(chosen, candidate, Exercise::domain);
            if (best < 0
                    || betterThan(
                            topicOk, topicCount, domainOk,
                            bestTopicOk, bestTopicCount, bestDomainOk)) {
                best = i;
                bestTopicOk = topicOk;
                bestTopicCount = topicCount;
                bestDomainOk = domainOk;
            }
        }
        return best;
    }

    /** Lexicographic comparison of the three scoring keys, each higher-is-better. */
    private static boolean betterThan(
            boolean topicOk, long topicCount, boolean domainOk,
            boolean bestTopicOk, long bestTopicCount, boolean bestDomainOk) {
        if (topicOk != bestTopicOk) {
            return topicOk;
        }
        if (topicCount != bestTopicCount) {
            return topicCount > bestTopicCount;
        }
        return domainOk && !bestDomainOk;
    }

    private static Map<String, Long> topicCounts(List<Exercise> remaining) {
        Map<String, Long> counts = new HashMap<>();
        for (Exercise exercise : remaining) {
            counts.merge(topicKey(exercise), 1L, Long::sum);
        }
        return counts;
    }

    /** The primary topic as a non-null map key; a sentinel stands in for an untagged rep. */
    private static String topicKey(Exercise exercise) {
        String topic = primaryTopic(exercise);
        return topic == null ? " untagged" : topic;
    }

    /**
     * Whether placing {@code candidate} would make the last {@link #maxConsecutiveSame}
     * chosen reps plus it all share the same key - a run one longer than the cap allows.
     * A {@code null} key (a rep with no topic) never forms a run, so untyped reps are
     * never treated as "the same pattern".
     */
    private boolean runWouldExceed(
            List<Exercise> chosen, Exercise candidate, Function<Exercise, String> key) {
        String candidateKey = key.apply(candidate);
        if (candidateKey == null || chosen.size() < maxConsecutiveSame) {
            return false;
        }
        for (int i = chosen.size() - maxConsecutiveSame; i < chosen.size(); i++) {
            if (!candidateKey.equals(key.apply(chosen.get(i)))) {
                return false;
            }
        }
        return true;
    }

    /** The rep's primary topic - its algorithmic pattern - or {@code null} when untagged. */
    private static String primaryTopic(Exercise exercise) {
        return exercise.topics().isEmpty() ? null : exercise.topics().get(0);
    }
}
