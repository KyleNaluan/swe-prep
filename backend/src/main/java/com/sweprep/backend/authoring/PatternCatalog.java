package com.sweprep.backend.authoring;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The lookup the pattern-identification rep (issue #9) derives from - the one rep
 * type that comes from a problem's <em>statement</em> rather than its reference
 * solution: an author already names the algorithmic shape of a problem when they
 * tag its {@code topics}, so this catalog maps that declared tag onto a canonical
 * pattern label rather than asking anything new of the author or attempting to
 * infer a pattern from prose.
 *
 * <p>{@link #patternFor} returns empty when none of a problem's topics names a
 * known pattern (e.g. a problem tagged only {@code ["array"]}), which is a
 * deliberate skip rather than a guess - {@code RepDeriver} omits the rep entirely
 * rather than emit a pattern-identification question with no real signal behind
 * the answer key.
 */
final class PatternCatalog {

    private PatternCatalog() {}

    /** One canonical pattern: its label, and why a learner might wrongly pick it for a problem it doesn't fit. */
    private record Pattern(String label, String misconceptionIfWronglyChosen) {}

    /**
     * Topic tag (normalised: lowercase, non-alphanumeric collapsed to '-') to
     * canonical pattern label. Ordered so the first matching topic in a problem's
     * declared list wins deterministically.
     */
    private static final Map<String, String> TOPIC_TO_LABEL = new LinkedHashMap<>();

    /** Canonical patterns in a fixed order, keyed by label - the distractor pool. */
    private static final Map<String, Pattern> PATTERNS = new LinkedHashMap<>();

    private static void pattern(String label, String misconceptionIfWronglyChosen, String... topics) {
        PATTERNS.put(label, new Pattern(label, misconceptionIfWronglyChosen));
        for (String topic : topics) {
            TOPIC_TO_LABEL.put(topic, label);
        }
    }

    static {
        pattern(
                "Two pointers",
                "reaches for two pointers when the input has no monotonic order to scan from both ends",
                "two-pointer", "two-pointers");
        pattern(
                "Sliding window",
                "reaches for a window because the input is contiguous, missing that there is no fixed- "
                        + "or variable-size subarray/substring goal here",
                "sliding-window");
        pattern(
                "Binary search",
                "sees a sorted or monotonic property and assumes binary search, forgetting it narrows a "
                        + "single search space rather than solving this problem's actual goal",
                "binary-search");
        pattern(
                "Hash map lookup",
                "defaults to hashing for O(1) lookup without checking whether this problem's structure "
                        + "actually needs one",
                "hash-map", "hashing", "hash-set");
        pattern(
                "Sort first",
                "assumes sorting is necessary, adding an unneeded O(n log n) pass where a single scan "
                        + "would do",
                "sorting");
        pattern(
                "Stack",
                "reaches for a stack for nested or matching structure that is not actually present here",
                "stack");
        pattern(
                "Graph traversal (DFS/BFS)",
                "assumes a graph or tree structure when the input is a flat array or string with no "
                        + "adjacency to traverse",
                "graph", "dfs", "bfs", "tree", "traversal");
        pattern(
                "Dynamic programming",
                "assumes overlapping subproblems exist without identifying any actual repeated state to "
                        + "memoise",
                "dynamic-programming", "dp", "memoization");
        pattern(
                "Greedy",
                "assumes a locally optimal choice is globally optimal without an exchange argument to "
                        + "justify it",
                "greedy");
        pattern(
                "Backtracking",
                "assumes an exhaustive search over choices is needed when a direct or greedy approach "
                        + "solves the problem",
                "backtracking");
        pattern(
                "Heap / priority queue",
                "assumes a running top-k or an ordering that changes under mutation, which is not what "
                        + "this problem asks for",
                "heap", "priority-queue");
        pattern(
                "Linked list traversal",
                "assumes a linked-list pointer-chasing shape when the input is a plain array",
                "linked-list");
    }

    /** The canonical pattern label the first recognised topic in {@code topics} names, if any. */
    static Optional<String> patternFor(List<String> topics) {
        for (String topic : topics) {
            String normalised = topic.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
            String label = TOPIC_TO_LABEL.get(normalised);
            if (label != null) {
                return Optional.of(label);
            }
        }
        return Optional.empty();
    }

    /** Up to {@code count} other patterns (never {@code correct}), each with its misconception, in catalog order. */
    static List<Distractor> distractors(String correct, int count) {
        List<Distractor> result = new ArrayList<>();
        for (Pattern candidate : PATTERNS.values()) {
            if (candidate.label().equals(correct)) {
                continue;
            }
            result.add(new Distractor(candidate.label(), candidate.misconceptionIfWronglyChosen()));
            if (result.size() == count) {
                break;
            }
        }
        return result;
    }

    /** A candidate wrong pattern and why a learner might wrongly pick it. */
    record Distractor(String label, String misconception) {}
}
