package com.sweprep.backend.language;

import java.util.List;
import java.util.Map;

/**
 * The source a language adapter generates to run a submission against test cases.
 *
 * @param sourceFiles      filename to source text for every generated file that
 *                         must compile alongside the submission (the harness, and
 *                         any support type it needs)
 * @param mainClass        the class whose {@code main} the runner invokes
 * @param runtimeClasspath extra classpath entries the generated code needs at
 *                         compile and run time (e.g. the JSON library it imports)
 */
public record GeneratedHarness(
        Map<String, String> sourceFiles, String mainClass, List<String> runtimeClasspath) {

    public GeneratedHarness {
        sourceFiles = Map.copyOf(sourceFiles);
        runtimeClasspath = List.copyOf(runtimeClasspath);
    }
}
