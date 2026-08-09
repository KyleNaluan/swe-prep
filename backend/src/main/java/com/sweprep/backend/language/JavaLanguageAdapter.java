package com.sweprep.backend.language;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sweprep.backend.exercise.DataType;
import com.sweprep.backend.exercise.Signature;
import com.sweprep.backend.exercise.Signature.Parameter;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Generates the Java stub and harness for an exercise from its language-neutral
 * {@link Signature}. The harness uses Jackson to deserialise each case's JSON
 * arguments into Java types, calls the submission, and records the raw return
 * value of each case as a JSON node into a result file - it does not decide
 * pass/fail. Interpreting those results against the expected values, under the
 * exercise's comparison rule, is the grader's job. Only argument binding needs
 * per-type generated code.
 *
 * <p>The result goes to a dedicated file (the harness's second argument), never
 * to the submission's own stdout, so a submission that prints past the runner's
 * output cap and then returns correctly cannot truncate away its own result.
 */
@Component
public class JavaLanguageAdapter implements LanguageAdapter {

    /** The class the submission must define, and the file it is written to. */
    public static final String SUBMISSION_CLASS = "Solution";

    private static final String HARNESS_CLASS = "Harness";
    private static final String TIMING_HARNESS_CLASS = "TimingHarness";

    @Override
    public String languageId() {
        return "java";
    }

    @Override
    public String generateStub(Signature signature) {
        String params = signature.parameters().stream()
                .map(p -> declaredType(p.type()) + " " + p.name())
                .collect(Collectors.joining(", "));
        String returnType = declaredType(signature.returnType());
        return """
                class %s {
                    public %s %s(%s) {
                        // Write your solution here.
                        throw new UnsupportedOperationException("Implement %s");
                    }
                }
                """
                .formatted(SUBMISSION_CLASS, returnType, signature.methodName(), params, signature.methodName());
    }

    @Override
    public GeneratedHarness generateHarness(Signature signature) {
        String harness = buildHarness(signature);
        return new GeneratedHarness(
                Map.of(HARNESS_CLASS + ".java", harness), HARNESS_CLASS, jacksonClasspath());
    }

    @Override
    public GeneratedHarness generateTimingHarness(Signature signature) {
        String harness = buildTimingHarness(signature);
        return new GeneratedHarness(
                Map.of(TIMING_HARNESS_CLASS + ".java", harness), TIMING_HARNESS_CLASS, jacksonClasspath());
    }

    /**
     * The per-parameter argument-binding code and the call line both harnesses share:
     * deserialising each JSON argument into its Java type and calling the submission's
     * method with them, in signature order. Only argument binding needs generated code -
     * everything else about a harness is identical shape regardless of the problem.
     */
    private record Binding(String code, String call) {}

    private static Binding binding(Signature signature) {
        List<Parameter> parameters = signature.parameters();
        StringBuilder code = new StringBuilder();
        List<String> callArgs = new ArrayList<>();
        for (int i = 0; i < parameters.size(); i++) {
            Parameter parameter = parameters.get(i);
            String local = "arg" + i;
            callArgs.add(local);
            code.append("                %s %s = mapper.convertValue(input.get(%d), %s);%n"
                    .formatted(declaredType(parameter.type()), local, i, classLiteral(parameter.type())));
        }
        String call = "%s actual = solution.%s(%s);"
                .formatted(declaredType(signature.returnType()), signature.methodName(),
                        String.join(", ", callArgs));
        return new Binding(code.toString(), call);
    }

    private static String buildHarness(Signature signature) {
        Binding binding = binding(signature);

        return """
                import com.fasterxml.jackson.databind.JsonNode;
                import com.fasterxml.jackson.databind.ObjectMapper;
                import com.fasterxml.jackson.databind.node.ArrayNode;
                import com.fasterxml.jackson.databind.node.ObjectNode;
                import java.io.File;

                // Generated from the exercise signature - do not edit.
                public class %1$s {
                    public static void main(String[] args) throws Exception {
                        ObjectMapper mapper = new ObjectMapper();
                        JsonNode cases = mapper.readTree(new File(args[0]));
                        File resultFile = new File(args[1]);
                        %2$s solution = new %2$s();
                        ArrayNode results = mapper.createArrayNode();
                        for (JsonNode testCase : cases) {
                            JsonNode input = testCase.get("input");
                            ObjectNode entry = mapper.createObjectNode();
                            try {
                %3$s                %4$s
                                entry.set("returned", mapper.valueToTree(actual));
                            } catch (Throwable t) {
                                // A case whose call throws produces no answer; the grader fails it.
                                entry.put("threw", true);
                            }
                            results.add(entry);
                        }
                        // Written to a dedicated file, off the submission's own output channel,
                        // so a runaway print loop can never truncate the result away.
                        mapper.writeValue(resultFile, results);
                    }
                }
                """
                .formatted(HARNESS_CLASS, SUBMISSION_CLASS, binding.code(), binding.call());
    }

    /**
     * The timing harness (issue #17): first makes {@code warmup} untimed calls to let
     * the JVM's JIT reach a steady state, then times {@code repetitions} further calls
     * of the submission's method against the same input, recording each one's elapsed
     * nanoseconds (or that it threw) rather than comparing a return value.
     *
     * <p>The warm-up phase exists because its absence measurably wrecked results: a
     * submission cheap enough to auto-vectorize (a plain array sum, say) can show its
     * <em>first</em> few calls an order of magnitude slower than later ones simply
     * because the JIT has not yet compiled or vectorised the hot loop, which has
     * nothing to do with the algorithm's actual growth rate. Discarding that
     * transition before any timed sample is taken is standard microbenchmark practice
     * and is what lets the measured repetitions reflect steady-state cost.
     *
     * <p>Deliberately re-binds the arguments fresh from the input JSON before every
     * warm-up and timed call, rather than reusing one deserialised object: a solution
     * that mutates its input in place (e.g. sorts it) is then run fairly every time,
     * not only the first, since each call gets an unmutated copy. That re-binding -
     * like constructing a fresh {@code Solution} each time, so no accidental state (a
     * memoising field, say) leaks across calls - happens <em>before</em> the timer
     * starts on a timed call, so parsing and construction cost is never counted as
     * algorithm time.
     */
    private static String buildTimingHarness(Signature signature) {
        Binding binding = binding(signature);

        return """
                import com.fasterxml.jackson.databind.JsonNode;
                import com.fasterxml.jackson.databind.ObjectMapper;
                import com.fasterxml.jackson.databind.node.ArrayNode;
                import com.fasterxml.jackson.databind.node.ObjectNode;
                import java.io.File;

                // Generated from the exercise signature - do not edit. Growing-input timing
                // mode (issue #17): one size per invocation (the caller runs this once per
                // measured size); a discarded warm-up phase precedes the timed repetitions.
                public class %1$s {
                    // Every call's result is stashed here, inside the timed (or warm-up)
                    // window, so the JIT can never prove a call's result is unobserved and
                    // eliminate the whole call as dead code - a real risk for a submission as
                    // cheap as a single array pass (a static field write is a globally
                    // observable side effect the compiler must keep, unlike an unread local).
                    static Object sink;

                    public static void main(String[] args) throws Exception {
                        ObjectMapper mapper = new ObjectMapper();
                        JsonNode input = mapper.readTree(new File(args[0]));
                        int warmup = Integer.parseInt(args[1]);
                        int repetitions = Integer.parseInt(args[2]);
                        File resultFile = new File(args[3]);

                        for (int rep = 0; rep < warmup; rep++) {
                            %2$s solution = new %2$s();
                            try {
                %3$s                %4$s
                                sink = actual;
                            } catch (Throwable t) {
                                // A warm-up call throwing is not itself a failure - only the
                                // timed repetitions below need a usable sample.
                            }
                        }

                        ArrayNode results = mapper.createArrayNode();
                        for (int rep = 0; rep < repetitions; rep++) {
                            %2$s solution = new %2$s();
                            ObjectNode entry = mapper.createObjectNode();
                            try {
                %3$s                long startNanos = System.nanoTime();
                                %4$s
                                sink = actual;
                                long elapsedNanos = System.nanoTime() - startNanos;
                                entry.put("elapsedNanos", elapsedNanos);
                            } catch (Throwable t) {
                                // This repetition's generated input produced no timing sample;
                                // the measurer treats the size as unusable rather than guessing.
                                entry.put("threw", true);
                            }
                            results.add(entry);
                        }
                        mapper.writeValue(resultFile, results);
                    }
                }
                """
                .formatted(TIMING_HARNESS_CLASS, SUBMISSION_CLASS, binding.code(), binding.call());
    }

    private static String declaredType(DataType type) {
        return switch (type) {
            case INT -> "int";
            case INT_ARRAY -> "int[]";
            case INT_MATRIX -> "int[][]";
            case BOOLEAN -> "boolean";
            case STRING -> "String";
        };
    }

    private static String classLiteral(DataType type) {
        return switch (type) {
            case INT -> "int.class";
            case INT_ARRAY -> "int[].class";
            case INT_MATRIX -> "int[][].class";
            case BOOLEAN -> "boolean.class";
            case STRING -> "String.class";
        };
    }

    /**
     * The jar locations of the Jackson classes the harness imports, resolved from
     * where those classes were actually loaded from. This is robust regardless of
     * how the launcher set {@code java.class.path} (surefire, for instance, may
     * hand the JVM a booter jar rather than the real dependency jars).
     */
    private static List<String> jacksonClasspath() {
        return List.of(ObjectMapper.class, JsonNode.class, JsonParser.class, JsonProperty.class).stream()
                .map(JavaLanguageAdapter::codeSourceOf)
                .distinct()
                .toList();
    }

    private static String codeSourceOf(Class<?> type) {
        CodeSource codeSource = type.getProtectionDomain().getCodeSource();
        if (codeSource == null || codeSource.getLocation() == null) {
            throw new IllegalStateException(
                    "Cannot locate the jar for " + type.getName() + " to build the harness classpath");
        }
        try {
            return java.nio.file.Path.of(codeSource.getLocation().toURI()).toString();
        } catch (Exception e) {
            throw new IllegalStateException("Malformed code source location for " + type.getName(), e);
        }
    }
}
