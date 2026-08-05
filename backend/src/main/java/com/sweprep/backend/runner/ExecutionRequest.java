package com.sweprep.backend.runner;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Everything a {@link Runner} needs to compile and run one program, and nothing
 * about test cases or verdicts: the runner only executes.
 *
 * @param sourceFiles filename to source text for every file that must compile
 *                    (for Java, e.g. {@code Solution.java} and {@code Harness.java})
 * @param dataFiles   filename to content for non-source files written alongside
 *                    the sources (e.g. the test cases as JSON), referenced by the
 *                    program via {@link #args}
 * @param mainClass   the class whose {@code main} is invoked
 * @param args        program arguments; relative filenames resolve against the
 *                    work directory the runner creates
 * @param classpath   extra classpath entries the program needs at compile and run
 *                    time (e.g. the JSON library the generated harness imports)
 * @param timeout     wall-clock limit on execution; generous enough not to fail a
 *                    slow-but-correct solution, tight enough to catch a loop
 */
public record ExecutionRequest(
        Map<String, String> sourceFiles,
        Map<String, String> dataFiles,
        String mainClass,
        List<String> args,
        List<String> classpath,
        Duration timeout) {

    public ExecutionRequest {
        sourceFiles = Map.copyOf(sourceFiles);
        dataFiles = Map.copyOf(dataFiles);
        args = List.copyOf(args);
        classpath = List.copyOf(classpath);
    }
}
