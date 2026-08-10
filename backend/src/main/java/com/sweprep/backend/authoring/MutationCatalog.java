package com.sweprep.backend.authoring;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small, fixed catalog of single-line source mutations - the deterministic
 * alternative to an LLM-drafted bug the task brief asks for ("mutate AST/lines").
 * Every operator is a pure text transform of one line; nothing here executes code
 * or judges whether a mutation is genuinely a bug - that verification is {@code
 * RepDeriver}'s job, using {@link ReferenceExecutor} to compile and run each
 * candidate for real. Keeping this class free of execution keeps it trivially
 * unit-testable and keeps the "is this actually wrong" question answered
 * empirically rather than by trusting a regex.
 *
 * <p>{@link #candidates(String)} enumerates every {@link MutationCandidate} a
 * fixed line-then-operator walk over the source produces, in a stable order
 * (ascending line, then catalog order): both spot-the-bug's "first mutation that
 * compiles and fails a case" search and fill-in-the-blank's "other plausible
 * lines" distractor pool draw from this same ordered list, so a given source
 * string always derives the same reps.
 */
final class MutationCatalog {

    private MutationCatalog() {}

    /** The kind of change a mutation makes, used to build its human-readable description. */
    enum Category {
        RELATIONAL_BOUNDARY("off-by-one boundary", "a `<`/`<=` (or `>`/`>=`) comparison shifted by one, changing which elements are included"),
        EQUALITY_FLIP("inverted equality check", "an `==` became `!=` (or vice versa), so the branch now runs on exactly the wrong condition"),
        LOGICAL_FLIP("flipped logical operator", "an `&&` became `||` (or vice versa), changing which combinations satisfy the condition"),
        INCREMENT_FLIP("flipped increment direction", "a `++` became `--` (or vice versa), so the loop variable now moves the wrong way"),
        ARITHMETIC_OFFSET("arithmetic off-by-one", "a `+ 1`/`- 1` adjustment was flipped or dropped, shifting a computed index by two instead of the intended one"),
        LITERAL_BUMP("wrong seed or bound value", "a literal used to seed or bound this computation was changed by one, so it starts from (or stops at) the wrong value");

        final String label;
        final String genericDescription;

        Category(String label, String genericDescription) {
            this.label = label;
            this.genericDescription = genericDescription;
        }
    }

    private record Operator(Category category, boolean conditionLineOnly, Function<String, Optional<String>> mutate) {}

    private static final Pattern RELATIONAL =
            Pattern.compile("(?<![<>=!])(<=|>=|<(?!<)|>(?!>))(?!=)");
    private static final Pattern EQUALITY = Pattern.compile("(==|!=)");
    private static final Pattern LOGICAL = Pattern.compile("(&&|\\|\\|)");
    private static final Pattern INCREMENT = Pattern.compile("(\\+\\+|--)");
    private static final Pattern ARITHMETIC_PLUS_ONE = Pattern.compile("\\+\\s*1\\b");
    private static final Pattern ARITHMETIC_MINUS_ONE = Pattern.compile("-\\s*1\\b");
    private static final Pattern INT_LITERAL = Pattern.compile("(?<![\\w.])(\\d+)(?![\\w.])");

    private static final List<Operator> OPERATORS = List.of(
            new Operator(Category.RELATIONAL_BOUNDARY, true, MutationCatalog::flipRelational),
            new Operator(Category.EQUALITY_FLIP, true, MutationCatalog::flipEquality),
            new Operator(Category.LOGICAL_FLIP, true, MutationCatalog::flipLogical),
            new Operator(Category.INCREMENT_FLIP, false, MutationCatalog::flipIncrement),
            new Operator(Category.ARITHMETIC_OFFSET, false, MutationCatalog::flipArithmeticOffset),
            new Operator(Category.LITERAL_BUMP, false, MutationCatalog::bumpFirstLiteral));

    /** Every candidate single-line mutation of {@code source}, in a stable, deterministic order. */
    static List<MutationCandidate> candidates(String source) {
        String[] lines = source.split("\n", -1);
        List<MutationCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (!isMutableLine(line)) {
                continue;
            }
            boolean conditionLine = isConditionLine(line);
            for (Operator operator : OPERATORS) {
                if (operator.conditionLineOnly() && !conditionLine) {
                    continue;
                }
                Optional<String> mutated = operator.mutate().apply(line);
                if (mutated.isPresent() && !mutated.get().equals(line)) {
                    candidates.add(new MutationCandidate(i, line, mutated.get(), operator.category()));
                }
            }
        }
        return candidates;
    }

    private static boolean isMutableLine(String line) {
        String trimmed = line.strip();
        return !trimmed.isEmpty() && !trimmed.startsWith("//") && !trimmed.startsWith("*")
                && !trimmed.startsWith("/*") && !trimmed.startsWith("import ") && !trimmed.startsWith("package ");
    }

    /**
     * Whether a line is a control-flow condition, the only place the relational,
     * equality and logical mutators are allowed to touch - generic type parameters
     * (e.g. {@code Map<Integer, Integer>}) use the same {@code <}/{@code >} tokens as
     * a comparison, so restricting those three operators to lines that plainly open a
     * conditional avoids mangling a declaration into something that will not compile.
     */
    private static boolean isConditionLine(String line) {
        return line.contains("if (") || line.contains("if(") || line.contains("while (")
                || line.contains("while(") || line.contains("for (") || line.contains("for(");
    }

    private static Optional<String> flipRelational(String line) {
        Matcher matcher = RELATIONAL.matcher(line);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String token = matcher.group(1);
        String flipped = switch (token) {
            case "<" -> "<=";
            case "<=" -> "<";
            case ">" -> ">=";
            case ">=" -> ">";
            default -> token;
        };
        return Optional.of(line.substring(0, matcher.start()) + flipped + line.substring(matcher.end()));
    }

    private static Optional<String> flipEquality(String line) {
        return flipFirst(line, EQUALITY, token -> token.equals("==") ? "!=" : "==");
    }

    private static Optional<String> flipLogical(String line) {
        return flipFirst(line, LOGICAL, token -> token.equals("&&") ? "||" : "&&");
    }

    private static Optional<String> flipIncrement(String line) {
        return flipFirst(line, INCREMENT, token -> token.equals("++") ? "--" : "++");
    }

    private static Optional<String> flipArithmeticOffset(String line) {
        Matcher plus = ARITHMETIC_PLUS_ONE.matcher(line);
        if (plus.find()) {
            return Optional.of(line.substring(0, plus.start()) + "- 1" + line.substring(plus.end()));
        }
        Matcher minus = ARITHMETIC_MINUS_ONE.matcher(line);
        if (minus.find()) {
            return Optional.of(line.substring(0, minus.start()) + "+ 1" + line.substring(minus.end()));
        }
        return Optional.empty();
    }

    private static Optional<String> bumpFirstLiteral(String line) {
        Matcher matcher = INT_LITERAL.matcher(line);
        if (!matcher.find()) {
            return Optional.empty();
        }
        int value = Integer.parseInt(matcher.group(1));
        String bumped = Integer.toString(value + 1);
        return Optional.of(line.substring(0, matcher.start()) + bumped + line.substring(matcher.end()));
    }

    private static Optional<String> flipFirst(String line, Pattern pattern, Function<String, String> flip) {
        Matcher matcher = pattern.matcher(line);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String flipped = flip.apply(matcher.group(1));
        return Optional.of(line.substring(0, matcher.start()) + flipped + line.substring(matcher.end()));
    }

    /**
     * One candidate single-line change: which line, its original and mutated text,
     * and the {@link Category} of change. Applying it to a full source string is a
     * pure substitution ({@link #applyTo}); nothing here says whether the result
     * compiles or behaves differently - that is verified empirically by whoever asks.
     */
    record MutationCandidate(int lineIndex, String originalLine, String mutatedLine, Category category) {

        /** {@code source} with this candidate's line replaced by its mutated form. */
        String applyTo(String source) {
            String[] lines = source.split("\n", -1);
            lines[lineIndex] = mutatedLine;
            return String.join("\n", lines);
        }

        /** A human-readable, line-and-diff description of exactly this change. */
        String describe() {
            return "Line %d was changed from `%s` to `%s`."
                    .formatted(lineIndex + 1, originalLine.strip(), mutatedLine.strip());
        }
    }
}
