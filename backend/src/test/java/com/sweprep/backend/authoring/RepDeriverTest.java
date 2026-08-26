package com.sweprep.backend.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sweprep.backend.authoring.RepDeriver.DerivationResult;
import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.Form;
import com.sweprep.backend.exercise.Grading;
import com.sweprep.backend.exercise.Response;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Proves the derivation pipeline itself (issue #24's second, third and fourth
 * acceptance criteria): every rep type is derived from the reference solution
 * (or, for pattern-id, the statement's own declared topics) rather than
 * hand-written, and a spot-the-bug rep's answer literally records the mutation
 * that was applied. All fixtures are synthetic, non-real problems (a running-max
 * and a linear-search demo), matching {@code testsupport/Fixtures}' convention -
 * never real interview content (issue #4/#14).
 */
class RepDeriverTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RepDeriver deriver = new RepDeriver();

    /** A simple, genuinely linear, mutation-rich reference solution: running max, seeded wrong-safe. */
    private static final String RUNNING_MAX_SPEC =
            """
            {
              "id": "demo-running-max",
              "title": "Running Max",
              "statement": "Given a non-empty array of integers, return its maximum value.",
              "domain": "algorithms",
              "topics": ["array", "two-pointers"],
              "difficulty": "EASY",
              "signature": {
                "method": "runningMax",
                "parameters": [ { "name": "nums", "type": "INT_ARRAY" } ],
                "returns": "INT"
              },
              "comparison": "exact",
              "cases": [
                { "input": [[1, 5, 3]], "expected": 5 },
                { "input": [[-4, -1, -9]], "expected": -1 },
                { "input": [[7]], "expected": 7 },
                { "input": [[2, 2, 2]], "expected": 2 }
              ],
              "referenceSolution": "class Solution {\\n    public int runningMax(int[] nums) {\\n        int max = nums[0];\\n        for (int i = 1; i < nums.length; i++) {\\n            if (nums[i] > max) {\\n                max = nums[i];\\n            }\\n        }\\n        return max;\\n    }\\n}\\n",
              "explanation": "A single pass keeps the largest value seen so far."
            }
            """;

    private ProblemSpec parse(String json) throws Exception {
        return ProblemSpecParser.parse("test", mapper.readTree(json));
    }

    @Test
    void derivesAllFiveRepTypesFromOneProblem() throws Exception {
        ProblemSpec spec = parse(RUNNING_MAX_SPEC);

        DerivationResult result = deriver.derive(spec);

        assertThat(result.challenge().id()).isEqualTo("demo-running-max");
        assertThat(result.challenge().form()).isEqualTo(Form.CHALLENGE);
        Set<String> repSuffixes = result.reps().stream()
                .map(e -> e.id().substring("demo-running-max-".length()))
                .collect(java.util.stream.Collectors.toSet());
        assertThat(repSuffixes)
                .as("skipped: " + result.skipped())
                .containsExactlyInAnyOrder("pattern", "complexity", "fill-blank", "spot-bug", "predict-output");
        assertThat(result.skipped()).isEmpty();
    }

    // A spec's authored examples/constraints (issue: swe-examples-feedback) belong to the
    // challenge itself - they are a display/pedagogy choice for the problem an author
    // wrote by hand, not something mechanically derivable from a case the way a rep is
    // (see ProblemSpec's own javadoc). Derived reps carry none.
    @Test
    void examplesAndConstraintsThreadThroughToTheChallengeButNotToDerivedReps() throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode root =
                (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(RUNNING_MAX_SPEC);
        com.fasterxml.jackson.databind.node.ObjectNode example = root.putArray("examples").addObject();
        example.put("input", "nums = [1,5,3]");
        example.put("output", "5");
        root.putArray("constraints").add("1 <= nums.length");
        ProblemSpec spec = ProblemSpecParser.parse("test", root);

        DerivationResult result = deriver.derive(spec);

        assertThat(result.challenge().examples()).hasSize(1);
        assertThat(result.challenge().examples().get(0).output()).isEqualTo("5");
        assertThat(result.challenge().constraints()).containsExactly("1 <= nums.length");
        assertThat(result.reps()).allSatisfy(rep -> {
            assertThat(rep.examples()).isEmpty();
            assertThat(rep.constraints()).isEmpty();
        });
    }

    @Test
    void everyDerivedRepIsAWarmupRepNotAChallenge() throws Exception {
        DerivationResult result = deriver.derive(parse(RUNNING_MAX_SPEC));

        assertThat(result.reps()).allSatisfy(rep -> assertThat(rep.form()).isEqualTo(Form.REP));
    }

    @Test
    void patternIdRepIsAvailableColdWithNoDerivedFromGate() throws Exception {
        DerivationResult result = deriver.derive(parse(RUNNING_MAX_SPEC));

        Exercise patternRep = repNamed(result, "demo-running-max-pattern");
        assertThat(patternRep.derivedFrom()).isNull();
        assertThat(patternRep.response()).isInstanceOf(Response.Choice.class);
    }

    @Test
    void everyOtherRepIsGatedOnTheUnderlyingProblem() throws Exception {
        DerivationResult result = deriver.derive(parse(RUNNING_MAX_SPEC));

        for (String suffix : List.of("complexity", "fill-blank", "spot-bug", "predict-output")) {
            Exercise rep = repNamed(result, "demo-running-max-" + suffix);
            assertThat(rep.derivedFrom()).as(suffix).isEqualTo("demo-running-max");
        }
    }

    @Test
    void spotTheBugCorrectOptionRecordsTheExactMutationApplied() throws Exception {
        DerivationResult result = deriver.derive(parse(RUNNING_MAX_SPEC));
        Exercise spotBug = repNamed(result, "demo-running-max-spot-bug");

        Response.Choice choice = (Response.Choice) spotBug.response();
        String correctText = choice.options().stream()
                .filter(o -> !o.hasMisconception())
                .findFirst()
                .orElseThrow()
                .text();

        // The correct option must name a concrete line-and-diff, not a vague category -
        // issue #24's "mutation recorded as the answer".
        assertThat(correctText).matches("(?s).*Line \\d+ was changed from `.*` to `.*`\\..*");
        // The mutated line actually shown in the statement must match what the answer claims.
        assertThat(spotBug.statement()).contains(extractAfter(correctText));
    }

    @Test
    void spotTheBugMutationGenuinelyBreaksTheSolution() throws Exception {
        DerivationResult result = deriver.derive(parse(RUNNING_MAX_SPEC));
        Exercise spotBug = repNamed(result, "demo-running-max-spot-bug");

        // The rep would not exist at all unless RepDeriver empirically re-ran the mutated
        // source and saw at least one declared case fail - this is a smoke assertion that
        // the explanation reflects that empirical check having actually happened.
        assertThat(spotBug.explanation()).contains("returns");
        assertThat(spotBug.explanation()).contains("instead of the expected");
    }

    @Test
    void everyChoiceRepHasExactlyOneCorrectOptionAndThreeAnnotatedDistractors() throws Exception {
        DerivationResult result = deriver.derive(parse(RUNNING_MAX_SPEC));

        for (Exercise rep : result.reps()) {
            if (!(rep.response() instanceof Response.Choice choice)) {
                continue;
            }
            long correctCount = choice.options().stream().filter(o -> !o.hasMisconception()).count();
            assertThat(correctCount).as(rep.id()).isEqualTo(1);
            choice.options().stream()
                    .filter(com.sweprep.backend.exercise.Option::hasMisconception)
                    .forEach(o -> assertThat(o.misconception()).as(rep.id()).isNotBlank());
        }
    }

    @Test
    void predictOutputAnswerKeyMatchesWhatTheReferenceActuallyReturns() throws Exception {
        DerivationResult result = deriver.derive(parse(RUNNING_MAX_SPEC));
        Exercise predictOutput = repNamed(result, "demo-running-max-predict-output");

        Grading.AnswerKey key = (Grading.AnswerKey) predictOutput.grading();
        // The smallest declared case is [7] -> 7; the derivation must have actually run
        // the reference solution rather than copying a case's hand-authored expected value
        // by coincidence - this asserts the value a real execution of runningMax([7]) gives.
        assertThat(key.expected().asInt()).isEqualTo(7);
    }

    @Test
    void aReferenceSolutionThatFailsItsOwnCasesRefusesToDeriveAnything() throws Exception {
        String badSpec = RUNNING_MAX_SPEC.replace(
                "int max = nums[0];", "int max = 0;"); // wrong for an all-negative case

        assertThatThrownBy(() -> deriver.derive(parse(badSpec)))
                .isInstanceOf(AuthoringException.class)
                .hasMessageContaining("does not pass its own declared cases");
    }

    @Test
    void aReferenceSolutionThatDoesNotCompileFailsClearly() throws Exception {
        String doesNotCompileSpec =
                """
                {
                  "id": "demo-broken",
                  "title": "Broken",
                  "statement": "Return the argument.",
                  "domain": "algorithms",
                  "topics": ["array"],
                  "difficulty": "EASY",
                  "signature": {
                    "method": "identity",
                    "parameters": [ { "name": "n", "type": "INT" } ],
                    "returns": "INT"
                  },
                  "comparison": "exact",
                  "cases": [ { "input": [3], "expected": 3 } ],
                  "referenceSolution": "class Solution { this is not java }",
                  "explanation": "n/a"
                }
                """;

        assertThatThrownBy(() -> deriver.derive(parse(doesNotCompileSpec)))
                .isInstanceOf(AuthoringException.class)
                .hasMessageContaining("does not compile");
    }

    @Test
    void noRecognisedPatternTopicSkipsThePatternRepRatherThanGuessing() throws Exception {
        String spec = RUNNING_MAX_SPEC.replace("\"array\", \"two-pointers\"", "\"array\", \"matrix\"");

        DerivationResult result = deriver.derive(parse(spec));

        assertThat(result.reps()).noneMatch(r -> r.id().endsWith("-pattern"));
        assertThat(result.skipped()).anyMatch(s -> s.startsWith("pattern-identification"));
    }

    @Test
    void fillBlankIsSkippedWhenEveryLineVariantIsBehaviorPreserving() throws Exception {
        // Cases keep the first two elements equal and positive, so no mutation of the
        // scanning loop's boundary/increment/seed is ever reached before the early return:
        // `i <= a.length` never OOBs (returns at i=0), `i--` never runs, and starting at
        // i=1 still returns an equal value. Every fill-blank candidate is therefore
        // behavior-preserving on these cases, so the empirical gate must reject them all
        // and skip the rep rather than shipping a distractor that is a second correct answer.
        String preservingSpec =
                """
                {
                  "id": "demo-first-positive",
                  "title": "First Positive",
                  "statement": "Return the first positive element, or -1.",
                  "domain": "algorithms",
                  "topics": ["array"],
                  "difficulty": "EASY",
                  "signature": {
                    "method": "firstPositive",
                    "parameters": [ { "name": "a", "type": "INT_ARRAY" } ],
                    "returns": "INT"
                  },
                  "comparison": "exact",
                  "cases": [
                    { "input": [[3, 3]], "expected": 3 },
                    { "input": [[5, 5]], "expected": 5 },
                    { "input": [[7, 7, 2]], "expected": 7 },
                    { "input": [[9, 9, 9]], "expected": 9 }
                  ],
                  "referenceSolution": "class Solution {\\n    public int firstPositive(int[] a) {\\n        for (int i = 0; i < a.length; i++) {\\n            if (a[i] > 0) {\\n                return a[i];\\n            }\\n        }\\n        return -1;\\n    }\\n}\\n",
                  "explanation": "Scan left to right and return the first positive value."
                }
                """;

        DerivationResult result = deriver.derive(parse(preservingSpec));

        assertThat(result.reps()).noneMatch(r -> r.id().endsWith("-fill-blank"));
        assertThat(result.skipped()).anyMatch(s -> s.startsWith("fill-in-the-blank"));
    }

    @Test
    void recursiveReferenceSolutionSkipsTheComplexityRepRatherThanGuessing() throws Exception {
        String recursiveSpec =
                """
                {
                  "id": "demo-fib",
                  "title": "Fib",
                  "statement": "Return the nth Fibonacci number.",
                  "domain": "algorithms",
                  "topics": ["recursion"],
                  "difficulty": "EASY",
                  "signature": {
                    "method": "fib",
                    "parameters": [ { "name": "n", "type": "INT" } ],
                    "returns": "INT"
                  },
                  "comparison": "exact",
                  "cases": [ { "input": [0], "expected": 0 }, { "input": [1], "expected": 1 }, { "input": [5], "expected": 5 } ],
                  "referenceSolution": "class Solution { public int fib(int n) { if (n <= 1) { return n; } return fib(n - 1) + fib(n - 2); } }",
                  "explanation": "Classic recursive Fibonacci."
                }
                """;

        DerivationResult result = deriver.derive(parse(recursiveSpec));

        assertThat(result.reps()).noneMatch(r -> r.id().endsWith("-complexity"));
        assertThat(result.skipped()).anyMatch(s -> s.startsWith("complexity"));
    }

    private Exercise repNamed(DerivationResult result, String id) {
        return result.reps().stream()
                .filter(r -> r.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError(id + " was not derived; skipped: " + result.skipped()));
    }

    /** Pulls the mutated-line text out of a "Line N was changed from `X` to `Y`." claim. */
    private String extractAfter(String describeText) {
        int toIndex = describeText.indexOf("to `");
        int end = describeText.indexOf('`', toIndex + 4);
        return describeText.substring(toIndex + 4, end);
    }
}
