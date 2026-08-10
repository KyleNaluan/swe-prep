package com.sweprep.backend.language;

import com.sweprep.backend.exercise.DataType;
import com.sweprep.backend.exercise.Signature;
import com.sweprep.backend.exercise.Signature.Parameter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Generates the Python stub and harness for an exercise from its language-neutral
 * {@link Signature} - the second {@link LanguageAdapter} (issue #26), proving the
 * tracer bullet's claim (issue #13) that a test case authored once as JSON runs in
 * any language: nothing in {@link com.sweprep.backend.exercise.TestCase} or {@link
 * com.sweprep.backend.exercise.Signature} changed to make this exist.
 *
 * <p>The harness parses each case's JSON arguments with the standard library {@code
 * json} module, calls the submission, and records the raw return value of each case
 * into a result file - it does not decide pass/fail, exactly like {@link
 * JavaLanguageAdapter}'s. Unlike Java, Python needs no per-parameter type-conversion
 * code: {@code json.load} already hands back native Python values (a JSON array is a
 * {@code list}, a JSON number an {@code int}/{@code float}, and so on), so binding an
 * argument is always the same one-line positional extraction regardless of {@link
 * DataType} - only the stub's type hints vary by declared type.
 *
 * <p>Generated with a plain {@link StringBuilder}, not the {@code """}-text-block
 * {@code .formatted()} style {@link JavaLanguageAdapter} uses: Python's indentation is
 * syntactically significant, so splicing generated lines into a template at an
 * implicit column (harmless in Java, where whitespace never changes meaning) is a
 * real correctness risk here. Every line this class emits carries its own explicit,
 * absolute indentation instead.
 */
@Component
public class PythonLanguageAdapter implements LanguageAdapter {

    /** The module the submission must define, and the file it is written to. */
    public static final String SUBMISSION_MODULE = "Solution";

    private static final String HARNESS_MODULE = "Harness";
    private static final String TIMING_HARNESS_MODULE = "TimingHarness";
    private static final String INDENT = "    ";

    @Override
    public String languageId() {
        return "python";
    }

    @Override
    public String submissionFileName() {
        return SUBMISSION_MODULE + ".py";
    }

    @Override
    public String generateStub(Signature signature) {
        String params = signature.parameters().stream()
                .map(p -> p.name() + ": " + declaredType(p.type()))
                .collect(Collectors.joining(", "));
        StringBuilder stub = new StringBuilder();
        stub.append("class ").append(SUBMISSION_MODULE).append(":\n");
        stub.append(INDENT)
                .append("def ")
                .append(signature.methodName())
                .append("(self, ")
                .append(params)
                .append(") -> ")
                .append(declaredType(signature.returnType()))
                .append(":\n");
        stub.append(INDENT).append(INDENT).append("# Write your solution here.\n");
        stub.append(INDENT)
                .append(INDENT)
                .append("raise NotImplementedError(\"Implement ")
                .append(signature.methodName())
                .append("\")\n");
        return stub.toString();
    }

    @Override
    public GeneratedHarness generateHarness(Signature signature) {
        return new GeneratedHarness(
                Map.of(HARNESS_MODULE + ".py", buildHarness(signature)), HARNESS_MODULE + ".py", List.of());
    }

    @Override
    public GeneratedHarness generateTimingHarness(Signature signature) {
        return new GeneratedHarness(
                Map.of(TIMING_HARNESS_MODULE + ".py", buildTimingHarness(signature)),
                TIMING_HARNESS_MODULE + ".py",
                List.of());
    }

    /**
     * Positional argument-binding lines shared by every harness: pull each argument
     * out of the case's already-parsed JSON list by index and bind it to a local name,
     * at the given absolute indentation. No per-{@link DataType} conversion is needed -
     * {@code json.load} already produced the right native Python value - only the
     * count and order of parameters drives this.
     */
    private static void appendBindings(StringBuilder out, Signature signature, String indent) {
        List<Parameter> parameters = signature.parameters();
        for (int i = 0; i < parameters.size(); i++) {
            out.append(indent).append("arg").append(i).append(" = case_input[").append(i).append("]\n");
        }
    }

    private static String callExpression(Signature signature) {
        String args = java.util.stream.IntStream.range(0, signature.parameters().size())
                .mapToObj(i -> "arg" + i)
                .collect(Collectors.joining(", "));
        return "solution." + signature.methodName() + "(" + args + ")";
    }

    private static String buildHarness(Signature signature) {
        StringBuilder h = new StringBuilder();
        h.append("# Generated from the exercise signature - do not edit.\n");
        h.append("import json\n");
        h.append("import sys\n\n");
        h.append("from ").append(SUBMISSION_MODULE).append(" import ").append(SUBMISSION_MODULE).append("\n\n\n");
        h.append("def main():\n");
        h.append(INDENT).append("with open(sys.argv[1]) as cases_file:\n");
        h.append(INDENT).append(INDENT).append("cases = json.load(cases_file)\n");
        h.append(INDENT).append("solution = ").append(SUBMISSION_MODULE).append("()\n");
        h.append(INDENT).append("results = []\n");
        h.append(INDENT).append("for case in cases:\n");
        h.append(INDENT).append(INDENT).append("case_input = case[\"input\"]\n");
        h.append(INDENT).append(INDENT).append("entry = {}\n");
        h.append(INDENT).append(INDENT).append("try:\n");
        appendBindings(h, signature, INDENT.repeat(3));
        h.append(INDENT.repeat(3)).append("entry[\"returned\"] = ").append(callExpression(signature)).append('\n');
        h.append(INDENT).append(INDENT).append("except Exception:\n");
        h.append(INDENT.repeat(3))
                .append("# A case whose call raises produces no answer; the grader fails it.\n");
        h.append(INDENT.repeat(3)).append("entry[\"threw\"] = True\n");
        h.append(INDENT).append(INDENT).append("results.append(entry)\n");
        h.append(INDENT)
                .append("# Written to a dedicated file, off the submission's own stdout, so a\n");
        h.append(INDENT).append("# runaway print loop can never truncate the result away.\n");
        h.append(INDENT).append("with open(sys.argv[2], \"w\") as result_file:\n");
        h.append(INDENT).append(INDENT).append("json.dump(results, result_file)\n\n\n");
        h.append("if __name__ == \"__main__\":\n");
        h.append(INDENT).append("main()\n");
        return h.toString();
    }

    /**
     * The timing harness (issue #17's "second execution mode"), the Python sibling of
     * {@link JavaLanguageAdapter}'s: a discarded warm-up phase followed by timed
     * repetitions, each measured with {@code time.perf_counter_ns()} - the standard
     * library's monotonic, highest-resolution clock, the direct Python analogue of
     * {@code System.nanoTime()}. A fresh {@code Solution} instance every call, and
     * arguments re-read from the parsed input every time, for the same fairness reason
     * as the Java harness: a solution that mutates its input is timed fairly on every
     * repetition, not just the first.
     */
    private static String buildTimingHarness(Signature signature) {
        StringBuilder h = new StringBuilder();
        h.append("# Generated from the exercise signature - do not edit. Growing-input timing\n");
        h.append("# mode (issue #17): one size per invocation; a discarded warm-up phase\n");
        h.append("# precedes the timed repetitions.\n");
        h.append("import json\n");
        h.append("import sys\n");
        h.append("import time\n\n");
        h.append("from ").append(SUBMISSION_MODULE).append(" import ").append(SUBMISSION_MODULE).append("\n\n\n");
        h.append("def main():\n");
        h.append(INDENT).append("with open(sys.argv[1]) as input_file:\n");
        h.append(INDENT).append(INDENT).append("case_input = json.load(input_file)\n");
        h.append(INDENT).append("warmup = int(sys.argv[2])\n");
        h.append(INDENT).append("repetitions = int(sys.argv[3])\n\n");

        h.append(INDENT).append("for _ in range(warmup):\n");
        h.append(INDENT).append(INDENT).append("solution = ").append(SUBMISSION_MODULE).append("()\n");
        h.append(INDENT).append(INDENT).append("try:\n");
        appendBindings(h, signature, INDENT.repeat(3));
        h.append(INDENT.repeat(3)).append(callExpression(signature)).append('\n');
        h.append(INDENT).append(INDENT).append("except Exception:\n");
        h.append(INDENT.repeat(3))
                .append("# A warm-up call raising is not itself a failure - only the timed\n");
        h.append(INDENT.repeat(3)).append("# repetitions below need a usable sample.\n");
        h.append(INDENT.repeat(3)).append("pass\n\n");

        h.append(INDENT).append("results = []\n");
        h.append(INDENT).append("for _ in range(repetitions):\n");
        h.append(INDENT).append(INDENT).append("solution = ").append(SUBMISSION_MODULE).append("()\n");
        h.append(INDENT).append(INDENT).append("entry = {}\n");
        h.append(INDENT).append(INDENT).append("try:\n");
        appendBindings(h, signature, INDENT.repeat(3));
        h.append(INDENT.repeat(3)).append("start_nanos = time.perf_counter_ns()\n");
        h.append(INDENT.repeat(3)).append(callExpression(signature)).append('\n');
        h.append(INDENT.repeat(3))
                .append("entry[\"elapsedNanos\"] = time.perf_counter_ns() - start_nanos\n");
        h.append(INDENT).append(INDENT).append("except Exception:\n");
        h.append(INDENT.repeat(3))
                .append("# This repetition produced no timing sample; the measurer treats\n");
        h.append(INDENT.repeat(3)).append("# the size as unusable rather than guessing.\n");
        h.append(INDENT.repeat(3)).append("entry[\"threw\"] = True\n");
        h.append(INDENT).append(INDENT).append("results.append(entry)\n\n");

        h.append(INDENT).append("with open(sys.argv[4], \"w\") as result_file:\n");
        h.append(INDENT).append(INDENT).append("json.dump(results, result_file)\n\n\n");
        h.append("if __name__ == \"__main__\":\n");
        h.append(INDENT).append("main()\n");
        return h.toString();
    }

    private static String declaredType(DataType type) {
        return switch (type) {
            case INT -> "int";
            case INT_ARRAY -> "list[int]";
            case INT_MATRIX -> "list[list[int]]";
            case BOOLEAN -> "bool";
            case STRING -> "str";
        };
    }
}
