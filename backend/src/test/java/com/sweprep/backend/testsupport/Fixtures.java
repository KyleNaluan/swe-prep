package com.sweprep.backend.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sweprep.backend.content.ContentException;
import com.sweprep.backend.exercise.CalloutKind;
import com.sweprep.backend.exercise.Comparison;
import com.sweprep.backend.exercise.Complexity;
import com.sweprep.backend.exercise.ComplexityCheck;
import com.sweprep.backend.exercise.DataType;
import com.sweprep.backend.exercise.Difficulty;
import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.ExerciseCatalog;
import com.sweprep.backend.exercise.Family;
import com.sweprep.backend.exercise.Form;
import com.sweprep.backend.exercise.Grading;
import com.sweprep.backend.exercise.Hint;
import com.sweprep.backend.exercise.InputGenerator;
import com.sweprep.backend.exercise.Lesson;
import com.sweprep.backend.exercise.LessonBlock;
import com.sweprep.backend.exercise.Option;
import com.sweprep.backend.exercise.Response;
import com.sweprep.backend.exercise.SelfExplainPrompt;
import com.sweprep.backend.exercise.Signature;
import com.sweprep.backend.exercise.Signature.Parameter;
import com.sweprep.backend.exercise.Stability;
import com.sweprep.backend.exercise.TestCase;
import java.util.List;
import java.util.Optional;

/**
 * Synthetic exercises for tests. These are deliberately <em>not</em> real
 * interview problems: no real problem statement, test data or reference solution
 * may live in this repo (issue #4/#14), so the suites prove the mechanism against
 * throwaway demo exercises ("return the two arguments", "the distinct values")
 * rather than against curated content, which is loaded from the private repo at
 * runtime and only smoke-tested when a local clone is present.
 */
public final class Fixtures {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private Fixtures() {}

    /**
     * A code exercise judged order-insensitively: implement {@code pair(a, b)} to
     * return the two arguments in any order. Exercises the test-case grader and the
     * order-insensitive comparison without being a real problem.
     */
    public static Exercise pairInAnyOrder() {
        Signature signature = new Signature(
                "pair",
                List.of(new Parameter("a", DataType.INT), new Parameter("b", DataType.INT)),
                DataType.INT_ARRAY);
        List<TestCase> cases = List.of(
                testCase("[1, 2]", "[1, 2]"),
                testCase("[5, 3]", "[3, 5]"),
                testCase("[7, 7]", "[7, 7]"));
        return new Exercise(
                "pair-in-any-order",
                "Pair In Any Order",
                "Return the two arguments in any order.",
                "algorithms",
                List.of("demo"),
                Difficulty.EASY,
                Form.CHALLENGE,
                new Response.Code(signature),
                new Grading.TestCases(Comparison.orderInsensitiveSequence(), cases),
                PAIR_HINTS);
    }

    /**
     * A three-rung hint ladder for {@link #pairInAnyOrder()}: pattern, approach, then
     * the key insight. Synthetic, like the exercise itself - it proves the ladder is
     * ordered and disclosed one rung at a time, not that any real problem is hinted.
     */
    public static final List<Hint> PAIR_HINTS = List.of(
            new Hint("Pattern", "This is just packing two values into an array."),
            new Hint("Approach", "Allocate an int[] of length two and fill both slots."),
            new Hint("Key insight", "Order does not matter here; return them in either order."));

    /** A correct solution to {@link #pairInAnyOrder()}. */
    public static final String PAIR_SOLUTION =
            """
            class Solution {
                public int[] pair(int a, int b) {
                    return new int[] {a, b};
                }
            }
            """;

    /**
     * A code exercise carrying a real complexity check (issue #17): count the distinct
     * values in an array, genuinely O(n) time and O(n) space, with an input generator
     * so the growing-input measurement can run end to end through the attempt
     * lifecycle. {@link #COMPLEXITY_LINEAR_SOLUTION} and {@link
     * #COMPLEXITY_QUADRATIC_SOLUTION} both answer its one correctness case identically;
     * only their scaling differs, which is the whole point.
     */
    public static Exercise complexityChallenge() {
        Signature signature = new Signature(
                "distinctCount", List.of(new Parameter("nums", DataType.INT_ARRAY)), DataType.INT);
        List<TestCase> cases = List.of(testCase("[[1, 2, 2, 3]]", "3"));
        ComplexityCheck check = new ComplexityCheck(
                Complexity.LINEAR,
                Complexity.LINEAR,
                new InputGenerator(List.of(new InputGenerator.Argument.ScalingIntArray(0, 1_000_000))));
        return new Exercise(
                "complexity-demo",
                "Distinct Count",
                "Count the distinct values in the array.",
                "algorithms",
                List.of("demo"),
                Difficulty.EASY,
                Form.CHALLENGE,
                new Response.Code(signature),
                new Grading.TestCases(Comparison.exact(), cases),
                List.of(),
                null,
                List.of(),
                null,
                null,
                null,
                check);
    }

    /**
     * The sibling of {@link #complexityChallenge()} whose complexity check carries a
     * target but no input generator - the "skip the check, keep the ask-and-reveal
     * flow" case (issue #17's explicit acceptance criterion).
     */
    public static Exercise complexityChallengeWithNoGenerator() {
        Exercise withGenerator = complexityChallenge();
        ComplexityCheck check = withGenerator.complexityCheck();
        return new Exercise(
                "complexity-demo-no-generator", withGenerator.title(), withGenerator.statement(),
                withGenerator.domain(), withGenerator.topics(), withGenerator.difficulty(),
                withGenerator.form(), withGenerator.response(), withGenerator.grading(),
                withGenerator.hints(), withGenerator.explanation(), withGenerator.family(),
                withGenerator.stability(), withGenerator.reviewed(), withGenerator.derivedFrom(),
                new ComplexityCheck(check.targetTime(), check.targetSpace(), null));
    }

    /** A correct, genuinely O(n) solution to {@link #complexityChallenge()}. */
    public static final String COMPLEXITY_LINEAR_SOLUTION =
            """
            import java.util.HashSet;
            class Solution {
                public int distinctCount(int[] nums) {
                    HashSet<Integer> seen = new HashSet<>();
                    int distinct = 0;
                    for (int x : nums) {
                        if (seen.add(x)) {
                            distinct++;
                        }
                    }
                    return distinct;
                }
            }
            """;

    /**
     * A correct but genuinely O(n^2) solution to {@link #complexityChallenge()} - the
     * same answers as {@link #COMPLEXITY_LINEAR_SOLUTION} on every case, but scanning
     * every prior element instead of hashing, which is the single case the empirical
     * check exists to catch when claimed as linear.
     */
    public static final String COMPLEXITY_QUADRATIC_SOLUTION =
            """
            class Solution {
                public int distinctCount(int[] nums) {
                    int distinct = 0;
                    for (int i = 0; i < nums.length; i++) {
                        boolean seenBefore = false;
                        for (int j = 0; j < i; j++) {
                            if (nums[j] == nums[i]) {
                                seenBefore = true;
                                break;
                            }
                        }
                        if (!seenBefore) {
                            distinct++;
                        }
                    }
                    return distinct;
                }
            }
            """;

    /**
     * A choice exercise judged by a fixed answer key - the demonstration that a
     * grader needs no runner. Options {@code A/B/C}, correct answer {@code B}. It
     * carries an {@code explanation} (issue #51) so the auto-on-wrong disclosure and the
     * on-request path can be proven; {@link #predictNumber()} is the sibling with none.
     */
    public static Exercise concept() {
        return new Exercise(
                "concept-demo",
                "Concept Demo",
                "Pick the correct option.",
                "fundamentals",
                List.of("demo"),
                Difficulty.EASY,
                Form.REP,
                new Response.Choice(List.of(
                        Option.distractor("A", "picks the first option without checking the others"),
                        Option.correct("B"),
                        Option.distractor("C", "assumes the last option is a catch-all"))),
                new Grading.AnswerKey(text("B"), Comparison.exact()),
                List.of(),
                CONCEPT_EXPLANATION,
                List.of(),
                null,
                null,
                null);
    }

    /** The explanation carried by {@link #concept()} (issue #51). */
    public static final String CONCEPT_EXPLANATION =
            "B is correct because it is the only option that holds in every case.";

    /**
     * A code exercise judged by a fixed numeric answer key - "predict the output".
     * Proves the answer-key grader is not string-only: the expected value is a
     * number, matched by magnitude through the exercise's comparison.
     */
    public static Exercise predictNumber() {
        Signature signature = new Signature(
                "value", List.of(new Parameter("n", DataType.INT)), DataType.INT);
        return new Exercise(
                "predict-number",
                "Predict Number",
                "What does this program print?",
                "fundamentals",
                List.of("demo"),
                Difficulty.EASY,
                Form.REP,
                new Response.Code(signature),
                new Grading.AnswerKey(json("42"), Comparison.exact()),
                List.of());
    }

    /**
     * A choice exercise whose options happen to be valid JSON literals
     * ({@code true}/{@code false}) but whose answer key is the string {@code "true"}.
     * Proves a JSON-looking option is not mis-graded against a string answer key.
     */
    public static Exercise booleanLookingChoice() {
        return new Exercise(
                "boolean-choice",
                "Boolean Choice",
                "True or false?",
                "fundamentals",
                List.of("demo"),
                Difficulty.EASY,
                Form.REP,
                new Response.Choice(List.of(
                        Option.correct("true"),
                        Option.distractor("false", "reads the predicate as negated"))),
                new Grading.AnswerKey(text("true"), Comparison.exact()),
                List.of());
    }

    /**
     * A free-text exercise judged by self-check - the produce-then-reveal format
     * that emits no machine verdict. The solver explains something in their own
     * words and grades themselves against the revealed model answer.
     */
    public static Exercise explain() {
        return new Exercise(
                "explain-demo",
                "Explain Demo",
                "Explain the concept in your own words.",
                "fundamentals",
                List.of("demo"),
                Difficulty.EASY,
                Form.REP,
                new Response.FreeText(),
                new Grading.SelfCheck("The model answer to compare yourself against."),
                List.of());
    }

    /**
     * A self-graded "explain in your own words" item shaped as an optional main (a {@code
     * CHALLENGE}), tagged {@code AIML} - the flagship consumer of production practice
     * (issue #41, design revision t3 section 3). FreeText + SelfCheck: produce, reveal the
     * model answer, self-rate; the machine never grades it.
     */
    public static Exercise explainChallenge() {
        return new Exercise(
                "explain-gradient-descent",
                "Explain gradient descent",
                "Explain, in your own words, what gradient descent does and why the learning "
                        + "rate matters.",
                "ai-ml",
                List.of("optimization"),
                Difficulty.MEDIUM,
                Form.CHALLENGE,
                new Response.FreeText(),
                new Grading.SelfCheck(
                        "Gradient descent iteratively steps the parameters in the direction of "
                                + "steepest descent of the loss - the negative gradient - to reduce "
                                + "error. The learning rate scales each step: too small and it crawls; "
                                + "too large and it overshoots or diverges."),
                List.of(),
                null,
                List.of(Family.AIML),
                Stability.STABLE,
                null,
                null);
    }

    // ---------------------------------------------------------------------------
    // A lesson with embedded ungraded self-explanation prompts (issue #41, delta D3). It is
    // read, never attempted: no response, no grading, no verdict. The prompts turn passive
    // reading into a generative activity - think, then reveal the model answer.

    /** A taught lesson carrying two ungraded self-explanation prompts. */
    public static Lesson lessonWithPrompts() {
        return new Lesson(
                "lesson-indexes",
                "Why an index sometimes is not used",
                "A B-tree index speeds lookups by key, but the planner will skip it when a query "
                        + "would read most of the table anyway, when the column is wrapped in a "
                        + "function, or when the types do not match.",
                "fundamentals",
                List.of("databases"),
                Difficulty.MEDIUM,
                List.of(),
                List.of(
                        new SelfExplainPrompt(
                                "Explain why wrapping an indexed column in a function (e.g. "
                                        + "LOWER(email)) can stop the index being used.",
                                "The index stores the raw column values, not the function's output, "
                                        + "so the planner cannot match LOWER(email) against it - a "
                                        + "separate expression index on LOWER(email) would be needed."),
                        new SelfExplainPrompt(
                                "Predict what the planner does when a query will return 90% of the "
                                        + "rows.",
                                "It prefers a sequential scan: reading nearly the whole table in "
                                        + "order is cheaper than the random I/O of walking the index "
                                        + "for almost every row.")),
                List.of(),
                List.of(Family.BACKEND, Family.DATA),
                Stability.STABLE,
                null);
    }

    // ---------------------------------------------------------------------------
    // A lesson authored with a structured body (issue #90 follow-on visual redesign): one
    // demonstration of every LessonBlock kind, so the new format has a real fixture behind
    // it rather than only the parser round-trip test. Content is synthetic, not real
    // interview material, matching this file's own convention.

    /** A taught lesson whose body is structured blocks rather than a single statement. */
    public static Lesson lessonWithStructuredBody() {
        return new Lesson(
                "lesson-hash-maps",
                "Hash maps: average O(1) lookup, and when it isn't",
                "A hash map trades memory for speed: a good hash function spreads keys evenly "
                        + "across buckets so most lookups touch only one entry.",
                "fundamentals",
                List.of("hash-map", "complexity"),
                Difficulty.EASY,
                List.of(),
                List.of(),
                List.of(
                        new LessonBlock.Heading(2, "How a lookup works"),
                        new LessonBlock.Paragraph(
                                "A hash map applies a hash function to the key, uses that to pick a "
                                        + "bucket, then scans the (usually short) chain in that bucket "
                                        + "for a matching key. Calling `map.get(key)` is average O(1) "
                                        + "because that chain rarely holds more than a couple of "
                                        + "entries."),
                        new LessonBlock.Example(
                                "java",
                                "Map<String, Integer> counts = new HashMap<>();\n"
                                        + "counts.put(\"apple\", 3);\n"
                                        + "counts.get(\"apple\");",
                                "Average-case O(1) lookup",
                                "3"),
                        new LessonBlock.Callout(
                                CalloutKind.WARNING,
                                "A poor hash function or an adversarial key set can collide many keys "
                                        + "into one bucket, degrading lookup to O(n) in the worst case."),
                        new LessonBlock.Heading(2, "Where the memory goes"),
                        new LessonBlock.ListBlock(
                                false,
                                List.of(
                                        "Each entry stores the key, the value, and a link to the next "
                                                + "entry in its bucket's chain.",
                                        "The bucket array itself is resized (usually doubled) once the "
                                                + "map gets too full, which is where an occasional `put` "
                                                + "costs more than O(1).")),
                        new LessonBlock.Table(
                                List.of("Operation", "Average", "Worst case"),
                                List.of(
                                        List.of("get", "O(1)", "O(n)"),
                                        List.of("put", "O(1) amortized", "O(n)")))),
                List.of(),
                Stability.STABLE,
                null);
    }
    // The five warm-up rep types (issue #9's resolution), one synthetic example of
    // each. They exercise rendering, grading and the warm-up endpoint end to end. Each
    // distractor names the specific misconception it targets (issue #42) - the same bar
    // real content is held to - so these throwaway reps double as worked examples of a
    // passing distractor set, even though the surrounding problems are not real content
    // (issue #4/#14). A pattern-identification rep is available cold (no derivedFrom);
    // the other four are gated on their underlying problem.

    /** Pattern identification: read a problem, name the pattern. Available cold. */
    public static Exercise patternIdRep() {
        return rep(
                "rep-pattern-id",
                "Pattern: sorted-pair sum",
                "A sorted array must be scanned for two entries summing to a target, in O(n) "
                        + "time and O(1) extra space. Which pattern fits best?",
                "two-pointer",
                new Response.Choice(List.of(
                        Option.correct("Two pointers"),
                        Option.distractor(
                                "Sliding window",
                                "reaches for a window because the array is contiguous, missing "
                                        + "that there is no fixed- or variable-size subarray here"),
                        Option.distractor(
                                "Binary search",
                                "sees 'sorted' and assumes binary search, forgetting it finds one "
                                        + "value, not a pair summing to a target"),
                        Option.distractor(
                                "Hash set",
                                "the natural unsorted-array answer, but it spends O(n) extra space "
                                        + "the sorted input makes unnecessary"))),
                new Grading.AnswerKey(text("Two pointers"), Comparison.exact()),
                "Two pointers from both ends run in O(n) with no extra space; a hash set is O(n) "
                        + "time but O(n) space, and sliding window needs a monotonic window it "
                        + "does not have here.",
                null);
    }

    /** Complexity of a snippet: pick its time complexity from the ladder. */
    public static Exercise complexityRep() {
        return rep(
                "rep-complexity",
                "Complexity: one hash pass",
                "A single loop inserts each of n elements into a hash set and checks membership "
                        + "once. What is its time complexity?",
                "hashing",
                new Response.Choice(List.of(
                        Option.distractor(
                                "O(1)",
                                "mistakes a single hash operation's O(1) cost for the whole loop's"),
                        Option.distractor(
                                "O(log n)",
                                "assumes any hashing structure implies logarithmic cost, confusing "
                                        + "it with a balanced tree"),
                        Option.correct("O(n)"),
                        Option.distractor(
                                "O(n^2)",
                                "double-counts the inner hash lookup as a nested scan"))),
                new Grading.AnswerKey(text("O(n)"), Comparison.exact()),
                "Each insert and lookup is O(1) average, done n times, so the pass is O(n) - not "
                        + "O(n^2), which would need a nested scan.",
                "sorted-pair-sum");
    }

    /** Fill in the missing line: choose the correct line from plausible near-misses. */
    public static Exercise fillBlankRep() {
        return rep(
                "rep-fill-blank",
                "Fill the blank: binary search",
                "In a binary search over an ascending array, after `int mid = lo + (hi - lo) / 2;` "
                        + "the code does `if (a[mid] < target) ___`. Which line belongs in the blank?",
                "binary-search",
                new Response.Choice(List.of(
                        Option.correct("lo = mid + 1;"),
                        Option.distractor(
                                "lo = mid;",
                                "moves the low bound to mid without excluding it, so the range can "
                                        + "stop shrinking and the search loops forever"),
                        Option.distractor(
                                "hi = mid - 1;",
                                "updates the wrong bound - narrows the high side when the target is "
                                        + "known to be above mid"),
                        Option.distractor(
                                "hi = mid;",
                                "both wrong bound and wrong direction, conflating the two branches "
                                        + "of the comparison"))),
                new Grading.AnswerKey(text("lo = mid + 1;"), Comparison.exact()),
                "The target is above mid, so the answer is to its right; moving to `mid + 1` "
                        + "excludes mid and guarantees progress. `lo = mid;` can loop forever.",
                "binary-search");
    }

    /** Predict the output: free text, matched exactly after normalisation. */
    public static Exercise predictOutputRep() {
        return rep(
                "rep-predict-output",
                "Predict output: reverse and mark",
                "`\"abc\"` is reversed and a `\"!\"` is appended. Type the exact string produced.",
                "strings",
                new Response.FreeText(),
                new Grading.AnswerKey(text("cba!"), Comparison.exact()),
                "Reversing \"abc\" gives \"cba\"; appending \"!\" yields \"cba!\".",
                "reverse-string");
    }

    /** Spot the bug: identify the defect in subtly wrong code. */
    public static Exercise spotBugRep() {
        return rep(
                "rep-spot-bug",
                "Spot the bug: running max",
                "This loop means to return the largest element: "
                        + "`int max = 0; for (int x : a) if (x > max) max = x; return max;`. "
                        + "What is wrong with it?",
                "arrays",
                new Response.Choice(List.of(
                        Option.correct("It returns 0 for an all-negative array"),
                        Option.distractor(
                                "It skips the last element",
                                "miscounts the for-each as an index loop with an off-by-one bound"),
                        Option.distractor(
                                "It is off by one on the first element",
                                "blames a fencepost error rather than the wrong initial value"),
                        Option.distractor(
                                "Nothing is wrong",
                                "reads the happy path only and misses the all-negative edge case"))),
                new Grading.AnswerKey(
                        text("It returns 0 for an all-negative array"), Comparison.exact()),
                "Seeding `max` with 0 instead of the first element (or Integer.MIN_VALUE) means a "
                        + "wholly negative array wrongly reports 0, an element that is not present.",
                "max-element");
    }

    /**
     * A predict-output free-text rep whose answer key is the given string, for proving
     * free-text normalisation on both the submission and the expected value.
     */
    public static Exercise freeTextWithExpected(String expected) {
        return rep(
                "rep-free-text",
                "Predict output",
                "Type the exact output.",
                "strings",
                new Response.FreeText(),
                new Grading.AnswerKey(text(expected), Comparison.exact()),
                "The expected value, spelled out.",
                "some-problem");
    }

    /**
     * A SQL query exercise (issue #25): a Query response graded against a two-row
     * result set on the "ecommerce" fixture, order-insensitive by default. Demonstrates
     * the second domain landing on the same {@code Exercise}/{@code Grader}/{@code Runner}
     * seam with no special-casing - notably, {@link Exercise#complexityCheck()} is simply
     * {@code null} via the existing convenience constructor, not a new carve-out.
     */
    public static Exercise sqlTopCustomers() {
        return new Exercise(
                "sql-top-customers",
                "Top Customers",
                "Return every customer id and name.",
                "sql",
                List.of("demo"),
                Difficulty.EASY,
                Form.CHALLENGE,
                new Response.Query(),
                new Grading.ResultSet("ecommerce", json("[[1, \"Alice\"], [2, \"Bob\"]]"), null),
                List.of());
    }

    /** {@link #sqlTopCustomers()}, but requiring the query's own row order (an {@code ORDER BY}). */
    public static Exercise sqlTopCustomersOrdered() {
        Exercise unordered = sqlTopCustomers();
        Grading.ResultSet grading = (Grading.ResultSet) unordered.grading();
        return new Exercise(
                "sql-top-customers-ordered",
                unordered.title(),
                unordered.statement(),
                unordered.domain(),
                unordered.topics(),
                unordered.difficulty(),
                unordered.form(),
                unordered.response(),
                new Grading.ResultSet(grading.fixture(), grading.expected(), Comparison.exact()),
                unordered.hints());
    }

    private static Exercise rep(
            String id,
            String title,
            String statement,
            String topic,
            Response response,
            Grading grading,
            String explanation,
            String derivedFrom) {
        return new Exercise(
                id,
                title,
                statement,
                "algorithms",
                List.of(topic),
                Difficulty.EASY,
                Form.REP,
                response,
                grading,
                List.of(),
                explanation,
                List.of(Family.CORE),
                Stability.STABLE,
                null,
                derivedFrom);
    }

    /** An in-memory catalog over the given exercises, in argument order. */
    public static ExerciseCatalog catalogOf(Exercise... exercises) {
        List<Exercise> all = List.of(exercises);
        return new ExerciseCatalog() {
            @Override
            public List<Exercise> all() {
                return all;
            }

            @Override
            public Optional<Exercise> byId(String id) {
                return all.stream().filter(e -> e.id().equals(id)).findFirst();
            }
        };
    }

    /** A catalog that fails to load, as a missing or malformed content path would. */
    public static ExerciseCatalog failingCatalog(String message) {
        return new ExerciseCatalog() {
            @Override
            public List<Exercise> all() {
                throw new ContentException(message);
            }

            @Override
            public Optional<Exercise> byId(String id) {
                throw new ContentException(message);
            }
        };
    }

    // --- Linked lists and binary trees (issue #6's adopted LeetCode serialisation) ------

    /**
     * A linked-list exercise: {@code dropFirst(head)} returns the list with its first
     * node removed. Synthetic like every other fixture here, and chosen because it
     * exercises the whole path a linked structure travels - built from a case's array,
     * handed to the solver as a {@code ListNode}, and serialised back for comparison -
     * without being a real interview problem (issue #4/#14).
     *
     * <p>Its cases deliberately include both spellings of "empty" ({@code []} and
     * {@code null}) and a one-element list, since the empty answer is exactly where a
     * serialisation convention is easiest to get wrong.
     */
    public static Exercise listDropFirst() {
        Signature signature = new Signature(
                "dropFirst", List.of(new Parameter("head", DataType.LIST_NODE)), DataType.LIST_NODE);
        List<TestCase> cases = List.of(
                testCase("[[1, 2, 3]]", "[2, 3]"),
                testCase("[[-1, -2]]", "[-2]"),
                testCase("[[7]]", "[]"),
                testCase("[[]]", "[]"),
                testCase("[null]", "[]"));
        return new Exercise(
                "list-drop-first-demo",
                "Drop First",
                "Return the list with its first node removed.",
                "algorithms",
                List.of("demo", "linked list"),
                Difficulty.EASY,
                Form.CHALLENGE,
                new Response.Code(signature),
                new Grading.TestCases(Comparison.exact(), cases),
                List.of());
    }

    /** A correct Java solution to {@link #listDropFirst()}. */
    public static final String LIST_DROP_FIRST_SOLUTION =
            """
            class Solution {
                public ListNode dropFirst(ListNode head) {
                    return head == null ? null : head.next;
                }
            }
            """;

    /** The same solution to {@link #listDropFirst()}, in Python. */
    public static final String LIST_DROP_FIRST_SOLUTION_PYTHON =
            """
            from Structures import ListNode


            class Solution:
                def dropFirst(self, head: ListNode) -> ListNode:
                    return None if head is None else head.next
            """;

    /**
     * The cycle case (issue #6, the shape LeetCode's linked-list-cycle problem needs):
     * {@code revisits(head)} answers whether walking the list ever returns to a node it
     * has already seen. The cycle travels as ordinary case data on the argument itself -
     * {@code { "values": [...], "pos": k }}, LeetCode's own way of posing it - and the
     * solver is handed the built, genuinely cyclic list, never the JSON.
     *
     * <p>Its return type is {@code BOOLEAN} on purpose: a cyclic structure is something a
     * submission is <em>given</em>, never something it hands back, which is why the
     * serialiser only ever has to handle acyclic values.
     */
    public static Exercise listRevisitsItself() {
        Signature signature = new Signature(
                "revisits", List.of(new Parameter("head", DataType.LIST_NODE)), DataType.BOOLEAN);
        List<TestCase> cases = List.of(
                testCase("[{ \"values\": [3, 2, 0, -4], \"pos\": 1 }]", "true"),
                testCase("[{ \"values\": [1, 2], \"pos\": 0 }]", "true"),
                testCase("[{ \"values\": [9], \"pos\": 0 }]", "true"),
                testCase("[{ \"values\": [1, 2], \"pos\": -1 }]", "false"),
                testCase("[[1, 2, 3]]", "false"),
                testCase("[[]]", "false"));
        return new Exercise(
                "list-revisits-demo",
                "Revisits Itself",
                "Return true when walking the list returns to a node it has already visited.",
                "algorithms",
                List.of("demo", "linked list"),
                Difficulty.EASY,
                Form.CHALLENGE,
                new Response.Code(signature),
                new Grading.TestCases(Comparison.exact(), cases),
                List.of());
    }

    /** A correct Java solution to {@link #listRevisitsItself()}. */
    public static final String LIST_REVISITS_SOLUTION =
            """
            class Solution {
                public boolean revisits(ListNode head) {
                    ListNode slow = head;
                    ListNode fast = head;
                    while (fast != null && fast.next != null) {
                        slow = slow.next;
                        fast = fast.next.next;
                        if (slow == fast) {
                            return true;
                        }
                    }
                    return false;
                }
            }
            """;

    /** The same solution to {@link #listRevisitsItself()}, in Python. */
    public static final String LIST_REVISITS_SOLUTION_PYTHON =
            """
            from Structures import ListNode


            class Solution:
                def revisits(self, head: ListNode) -> bool:
                    slow = head
                    fast = head
                    while fast is not None and fast.next is not None:
                        slow = slow.next
                        fast = fast.next.next
                        if slow is fast:
                            return True
                    return False
            """;

    /**
     * A binary-tree exercise: {@code dropRight(root)} returns the tree with the root's
     * right subtree removed. Synthetic, and shaped so the answer's level-order form is
     * genuinely different from the input's - including a case whose answer carries an
     * internal null and one whose trailing nulls must be trimmed, the two things a
     * level-order serialisation has to get right.
     */
    public static Exercise treeDropRight() {
        Signature signature = new Signature(
                "dropRight", List.of(new Parameter("root", DataType.TREE_NODE)), DataType.TREE_NODE);
        List<TestCase> cases = List.of(
                testCase("[[1, 2, 3]]", "[1, 2]"),
                testCase("[[3, 9, 20, null, null, 15, 7]]", "[3, 9]"),
                testCase("[[1, null, 2]]", "[1]"),
                testCase("[[5, 4, 6, 3, null, null, 7]]", "[5, 4, null, 3]"),
                testCase("[[]]", "[]"),
                testCase("[null]", "[]"));
        return new Exercise(
                "tree-drop-right-demo",
                "Drop Right",
                "Return the tree with the root's right subtree removed.",
                "algorithms",
                List.of("demo", "binary tree"),
                Difficulty.EASY,
                Form.CHALLENGE,
                new Response.Code(signature),
                new Grading.TestCases(Comparison.exact(), cases),
                List.of());
    }

    /** A correct Java solution to {@link #treeDropRight()}. */
    public static final String TREE_DROP_RIGHT_SOLUTION =
            """
            class Solution {
                public TreeNode dropRight(TreeNode root) {
                    if (root == null) {
                        return null;
                    }
                    return new TreeNode(root.val, root.left, null);
                }
            }
            """;

    /** The same solution to {@link #treeDropRight()}, in Python. */
    public static final String TREE_DROP_RIGHT_SOLUTION_PYTHON =
            """
            from Structures import TreeNode


            class Solution:
                def dropRight(self, root: TreeNode) -> TreeNode:
                    if root is None:
                        return None
                    return TreeNode(root.val, root.left, None)
            """;

    private static TestCase testCase(String input, String expected) {
        return new TestCase(json(input), json(expected));
    }

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException("Bad fixture JSON: " + raw, e);
        }
    }

    private static JsonNode text(String value) {
        return MAPPER.getNodeFactory().textNode(value);
    }
}
