package com.sweprep.backend.authoring;

import com.sweprep.backend.exercise.Complexity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A static, structural estimate of a reference solution's time complexity, used
 * only to derive the <em>complexity rep</em> (issue #9's "complexity of a
 * snippet" warm-up type) - not the issue #17 self-report/measurement flow, which
 * empirically times a solution and is unrelated to this class.
 *
 * <p>Deliberately a coarse heuristic (max loop nesting depth, with one pattern
 * for a halving loop), not a general-purpose analyzer: it counts {@code for}/
 * {@code while} nesting and recognises a single loop that repeatedly halves its
 * bound as logarithmic. Recursion and anything nesting past cubic are refused
 * ({@link #estimate} returns empty) rather than guessed, because a wrong answer
 * key silently shipped is worse than a rep skipped - the acceptance criterion
 * that every derived rep is presented for human verification before being
 * accepted is what makes an imperfect heuristic safe to ship at all.
 */
final class ComplexityHeuristic {

    private ComplexityHeuristic() {}

    private static final Pattern LOOP_OPEN = Pattern.compile("\\b(for|while)\\s*\\(");
    private static final Pattern HALVING = Pattern.compile("(/=\\s*2\\b|>>=\\s*1\\b|/\\s*2\\b|>>\\s*1\\b)");

    /** The estimated time complexity of {@code source}'s {@code methodName}, or empty if not confident. */
    static Optional<Complexity> estimate(String source, String methodName) {
        String[] lines = source.split("\n", -1);
        boolean recursive = countOccurrences(source, methodName == null ? null : methodName + "(") > 1;
        int depth = 0;
        int maxDepth = 0;
        boolean sawHalvingAtDepthOne = false;
        for (String rawLine : lines) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) {
                continue;
            }
            Matcher loopOpen = LOOP_OPEN.matcher(line);
            if (loopOpen.find()) {
                depth++;
                maxDepth = Math.max(maxDepth, depth);
            }
            // Checked for every line inside a depth-1 loop, not just its opening line:
            // the halving update ("mid = (lo + hi) / 2", "hi = mid - 1") is normally its
            // own statement in the loop body, not part of the "while (...)" line itself.
            if (depth == 1 && HALVING.matcher(line).find()) {
                sawHalvingAtDepthOne = true;
            }
            // Coarse brace accounting: fine for the straightforward, single-method
            // reference solutions this tool derives reps from, not a real parser.
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '}' && depth > 0) {
                    depth--;
                }
            }
        }
        if (recursive) {
            return Optional.empty();
        }
        return switch (maxDepth) {
            case 0 -> Optional.of(Complexity.CONSTANT);
            case 1 -> Optional.of(sawHalvingAtDepthOne ? Complexity.LOGARITHMIC : Complexity.LINEAR);
            case 2 -> Optional.of(Complexity.QUADRATIC);
            case 3 -> Optional.of(Complexity.CUBIC);
            default -> Optional.empty();
        };
    }

    /**
     * How many times {@code needle} occurs in {@code source} - used to detect
     * recursion as "the method's own name followed by '(' appears more than once"
     * (the declaration itself is always one such occurrence). Counting across the
     * whole source rather than line-by-line is deliberate: it works identically
     * whether the reference solution is conventionally formatted or collapsed onto
     * one line, and needs no assumption about an access modifier being present (a
     * reference solution's method needs none - the generated harness calls it from
     * an unnamed-package sibling class, so package-private compiles and runs fine).
     */
    private static long countOccurrences(String source, String needle) {
        if (needle == null || needle.isEmpty()) {
            return 0;
        }
        long count = 0;
        int from = 0;
        while (true) {
            int at = source.indexOf(needle, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + needle.length();
        }
    }

    /** Human Big-O notation for {@code complexity}, for display in a derived rep. */
    static String label(Complexity complexity) {
        return switch (complexity) {
            case CONSTANT -> "O(1)";
            case LOGARITHMIC -> "O(log n)";
            case LINEAR -> "O(n)";
            case LINEARITHMIC -> "O(n log n)";
            case QUADRATIC -> "O(n^2)";
            case CUBIC -> "O(n^3)";
            case EXPONENTIAL -> "O(2^n)";
        };
    }

    /** Up to {@code count} other complexity classes, closest first, for use as distractors. */
    static List<Complexity> nearestOthers(Complexity correct, int count) {
        List<Complexity> ladder = List.of(Complexity.values());
        int correctIndex = ladder.indexOf(correct);
        List<Complexity> others = new ArrayList<>(ladder);
        others.remove(correct);
        others.sort(Comparator.comparingInt(c -> Math.abs(ladder.indexOf(c) - correctIndex)));
        return others.subList(0, Math.min(count, others.size()));
    }

    /** Why a learner might wrongly pick {@code distractor} over {@code correct}. */
    static String misconception(Complexity correct, Complexity distractor) {
        if (distractor == Complexity.LOGARITHMIC) {
            return "assumes the loop's bound is halved each iteration, which this snippet does not do";
        }
        List<Complexity> ladder = List.of(Complexity.values());
        boolean coarser = ladder.indexOf(distractor) > ladder.indexOf(correct);
        return coarser
                ? "overcounts the nesting, treating an independent pass as if it ran inside the outer loop"
                : "undercounts the nesting, missing that this loop runs inside another rather than after it";
    }

    static String describe(Complexity complexity) {
        return complexity.name().toLowerCase(Locale.ROOT);
    }
}
