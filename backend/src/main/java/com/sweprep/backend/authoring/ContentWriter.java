package com.sweprep.backend.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sweprep.backend.exercise.Comparison;
import com.sweprep.backend.exercise.ComplexityCheck;
import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.Grading;
import com.sweprep.backend.exercise.Hint;
import com.sweprep.backend.exercise.InputGenerator;
import com.sweprep.backend.exercise.Option;
import com.sweprep.backend.exercise.Response;
import com.sweprep.backend.exercise.Signature;
import com.sweprep.backend.exercise.TestCase;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Serialises an {@link Exercise} to exactly the on-disk JSON shape {@code
 * ExerciseParser} reads (the content repo README's canonical format), and writes
 * a reference solution to {@code solutions/<id>.java} - the same convention the
 * seeded real content already follows (see {@code solutions/two-sum.java} in the
 * content repo). Every field this writes is one {@code ExerciseParser} already
 * round-trips; {@code RepDeriverContentRoundTripTest} loads the files this class
 * writes back through the real loader to prove the two never drift apart.
 */
final class ContentWriter {

    private final ObjectMapper mapper;

    ContentWriter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Writes {@code exercise} to {@code contentDir/<id>.json}, pretty-printed. */
    void writeExercise(Exercise exercise, Path contentDir) {
        Path target = contentDir.resolve(exercise.id() + ".json");
        writeJson(target, toJson(exercise));
    }

    /** Writes {@code source} to {@code contentDir/solutions/<id>.java}. */
    void writeSolution(String id, String source, Path contentDir) {
        Path solutionsDir = contentDir.resolve("solutions");
        try {
            Files.createDirectories(solutionsDir);
            Files.writeString(solutionsDir.resolve(id + ".java"), source, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AuthoringException("Failed to write reference solution for '" + id + "'", e);
        }
    }

    private void writeJson(Path target, ObjectNode node) {
        try {
            Files.writeString(
                    target, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node) + "\n",
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AuthoringException("Failed to write " + target, e);
        }
    }

    ObjectNode toJson(Exercise exercise) {
        ObjectNode root = mapper.createObjectNode();
        root.put("id", exercise.id());
        root.put("title", exercise.title());
        root.put("statement", exercise.statement());
        root.put("domain", exercise.domain());
        root.set("topics", stringArray(exercise.topics()));
        root.put("difficulty", exercise.difficulty().name());
        root.put("form", exercise.form().name());
        root.set("response", response(exercise.response()));
        root.set("grading", grading(exercise.grading()));
        if (!exercise.hints().isEmpty()) {
            root.set("hints", hints(exercise.hints()));
        }
        if (exercise.explanation() != null) {
            root.put("explanation", exercise.explanation());
        }
        if (!exercise.family().isEmpty()) {
            ArrayNode family = mapper.createArrayNode();
            exercise.family().forEach(f -> family.add(f.name()));
            root.set("family", family);
        }
        root.put("stability", exercise.stability().name());
        if (exercise.reviewed() != null) {
            root.put("reviewed", exercise.reviewed().toString());
        }
        if (exercise.derivedFrom() != null) {
            root.put("derivedFrom", exercise.derivedFrom());
        }
        if (exercise.complexityCheck() != null) {
            root.set("complexity", complexityCheck(exercise.complexityCheck()));
        }
        return root;
    }

    private ObjectNode response(Response response) {
        ObjectNode node = mapper.createObjectNode();
        switch (response) {
            case Response.Code code -> {
                node.put("kind", "code");
                node.set("signature", signature(code.signature()));
            }
            case Response.Choice choice -> {
                node.put("kind", "choice");
                ArrayNode options = mapper.createArrayNode();
                for (Option option : choice.options()) {
                    options.add(optionNode(option));
                }
                node.set("options", options);
            }
            case Response.FreeText ignored -> node.put("kind", "freeText");
            case Response.Query ignored ->
                throw new IllegalArgumentException(
                        "The authoring tool does not derive SQL (query) content");
        }
        return node;
    }

    private JsonNode optionNode(Option option) {
        if (!option.hasMisconception()) {
            return mapper.getNodeFactory().textNode(option.text());
        }
        ObjectNode node = mapper.createObjectNode();
        node.put("text", option.text());
        node.put("misconception", option.misconception());
        return node;
    }

    private ObjectNode signature(Signature signature) {
        ObjectNode node = mapper.createObjectNode();
        node.put("method", signature.methodName());
        ArrayNode parameters = mapper.createArrayNode();
        for (Signature.Parameter parameter : signature.parameters()) {
            ObjectNode paramNode = mapper.createObjectNode();
            paramNode.put("name", parameter.name());
            paramNode.put("type", parameter.type().name());
            parameters.add(paramNode);
        }
        node.set("parameters", parameters);
        node.put("returns", signature.returnType().name());
        return node;
    }

    private ObjectNode grading(Grading grading) {
        ObjectNode node = mapper.createObjectNode();
        switch (grading) {
            case Grading.TestCases testCases -> {
                node.put("kind", "testCases");
                node.put("comparison", comparisonName(testCases.comparison()));
                ArrayNode cases = mapper.createArrayNode();
                for (TestCase testCase : testCases.cases()) {
                    ObjectNode caseNode = mapper.createObjectNode();
                    caseNode.set("input", testCase.input());
                    caseNode.set("expected", testCase.expected());
                    cases.add(caseNode);
                }
                node.set("cases", cases);
            }
            case Grading.AnswerKey answerKey -> {
                node.put("kind", "answerKey");
                node.put("comparison", comparisonName(answerKey.comparison()));
                node.set("expected", answerKey.expected());
            }
            case Grading.SelfCheck selfCheck -> {
                node.put("kind", "selfCheck");
                node.put("modelAnswer", selfCheck.modelAnswer());
            }
            case Grading.ResultSet ignored ->
                throw new IllegalArgumentException(
                        "The authoring tool does not derive SQL (result-set) content");
        }
        return node;
    }

    private String comparisonName(Comparison comparison) {
        return switch (comparison) {
            case Comparison.Exact ignored -> "exact";
            case Comparison.OrderInsensitiveSequence ignored -> "orderInsensitiveSequence";
            case Comparison.SetEquality ignored -> "setEquality";
        };
    }

    private ArrayNode hints(java.util.List<Hint> hints) {
        ArrayNode array = mapper.createArrayNode();
        for (Hint hint : hints) {
            ObjectNode node = mapper.createObjectNode();
            node.put("name", hint.name());
            node.put("body", hint.body());
            array.add(node);
        }
        return array;
    }

    private ArrayNode stringArray(java.util.List<String> values) {
        ArrayNode array = mapper.createArrayNode();
        values.forEach(array::add);
        return array;
    }

    private ObjectNode complexityCheck(ComplexityCheck check) {
        ObjectNode node = mapper.createObjectNode();
        node.put("targetTime", check.targetTime().name());
        node.put("targetSpace", check.targetSpace().name());
        if (check.generator() != null) {
            node.set("generator", generator(check.generator()));
        }
        return node;
    }

    private ObjectNode generator(InputGenerator generator) {
        ObjectNode node = mapper.createObjectNode();
        ArrayNode arguments = mapper.createArrayNode();
        for (InputGenerator.Argument argument : generator.arguments()) {
            ObjectNode argNode = mapper.createObjectNode();
            switch (argument) {
                case InputGenerator.Argument.ScalingIntArray scaling -> {
                    argNode.put("kind", "scalingIntArray");
                    argNode.put("min", scaling.min());
                    argNode.put("max", scaling.max());
                }
                case InputGenerator.Argument.Fixed fixed -> {
                    argNode.put("kind", "fixed");
                    argNode.set("value", fixed.value());
                }
            }
            arguments.add(argNode);
        }
        node.set("arguments", arguments);
        return node;
    }
}
