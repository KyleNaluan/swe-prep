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

    private static String buildHarness(Signature signature) {
        List<Parameter> parameters = signature.parameters();
        StringBuilder binding = new StringBuilder();
        List<String> callArgs = new ArrayList<>();
        for (int i = 0; i < parameters.size(); i++) {
            Parameter parameter = parameters.get(i);
            String local = "arg" + i;
            callArgs.add(local);
            binding.append("                %s %s = mapper.convertValue(input.get(%d), %s);%n"
                    .formatted(declaredType(parameter.type()), local, i, classLiteral(parameter.type())));
        }
        String call = "%s actual = solution.%s(%s);"
                .formatted(declaredType(signature.returnType()), signature.methodName(),
                        String.join(", ", callArgs));

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
                .formatted(HARNESS_CLASS, SUBMISSION_CLASS, binding, call);
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
