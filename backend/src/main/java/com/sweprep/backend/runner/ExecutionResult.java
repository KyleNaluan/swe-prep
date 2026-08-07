package com.sweprep.backend.runner;

import java.util.Map;

/**
 * The raw outcome of one {@link Runner} execution. It reports what happened at
 * the level of the process - it did not compile, it ran, it was killed for
 * running too long - and leaves interpreting that into a pass/fail verdict to a
 * grader.
 *
 * @param outcome         what happened
 * @param exitCode        process exit code when {@code outcome} is
 *                        {@link Outcome#COMPLETED}, otherwise undefined
 * @param stdout          everything the program wrote to standard out
 * @param stderr          everything the program wrote to standard error
 * @param outputFiles     contents of the requested
 *                        {@link ExecutionRequest#outputFiles()} that existed when
 *                        the program exited, keyed by filename; absent files are
 *                        simply not present
 * @param compilerMessage compiler diagnostics when {@code outcome} is
 *                        {@link Outcome#COMPILE_ERROR}, otherwise empty
 */
public record ExecutionResult(
        Outcome outcome,
        int exitCode,
        String stdout,
        String stderr,
        Map<String, String> outputFiles,
        String compilerMessage) {

    public ExecutionResult {
        outputFiles = Map.copyOf(outputFiles);
    }

    public enum Outcome {
        /** The sources compiled and the program ran to completion. */
        COMPLETED,
        /** The sources did not compile; the program never ran. */
        COMPILE_ERROR,
        /** The program ran past its timeout and was killed. */
        TIMEOUT
    }

    static ExecutionResult compileError(String message) {
        return new ExecutionResult(Outcome.COMPILE_ERROR, -1, "", "", Map.of(), message);
    }

    static ExecutionResult timeout(String stdout, String stderr) {
        return new ExecutionResult(Outcome.TIMEOUT, -1, stdout, stderr, Map.of(), "");
    }

    static ExecutionResult completed(
            int exitCode, String stdout, String stderr, Map<String, String> outputFiles) {
        return new ExecutionResult(Outcome.COMPLETED, exitCode, stdout, stderr, outputFiles, "");
    }
}
