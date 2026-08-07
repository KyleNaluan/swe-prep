package com.sweprep.backend.runner;

import java.util.List;
import java.util.Map;

/**
 * The raw outcome of one {@link Runner} execution. It reports what happened at
 * the level of the process - it did not compile, it ran, it was killed for
 * running too long - and leaves interpreting that into a pass/fail verdict to a
 * grader.
 *
 * @param outcome              what happened
 * @param exitCode             process exit code when {@code outcome} is
 *                             {@link Outcome#COMPLETED}, otherwise undefined
 * @param stdout               everything the program wrote to standard out
 * @param stderr               everything the program wrote to standard error
 * @param outputFiles          contents of the requested
 *                             {@link ExecutionRequest#outputFiles()} that existed
 *                             when the program exited, keyed by filename; absent
 *                             files are simply not present
 * @param oversizedOutputFiles names of requested output files that existed but
 *                             exceeded the size the runner will read back into
 *                             heap, so their content was deliberately not loaded;
 *                             kept distinct from an absent or empty file
 * @param compilerMessage      compiler diagnostics when {@code outcome} is
 *                             {@link Outcome#COMPILE_ERROR}, otherwise empty
 */
public record ExecutionResult(
        Outcome outcome,
        int exitCode,
        String stdout,
        String stderr,
        Map<String, String> outputFiles,
        List<String> oversizedOutputFiles,
        String compilerMessage) {

    public ExecutionResult {
        outputFiles = Map.copyOf(outputFiles);
        oversizedOutputFiles = List.copyOf(oversizedOutputFiles);
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
        return new ExecutionResult(Outcome.COMPILE_ERROR, -1, "", "", Map.of(), List.of(), message);
    }

    static ExecutionResult timeout(String stdout, String stderr) {
        return new ExecutionResult(Outcome.TIMEOUT, -1, stdout, stderr, Map.of(), List.of(), "");
    }

    static ExecutionResult completed(
            int exitCode,
            String stdout,
            String stderr,
            Map<String, String> outputFiles,
            List<String> oversizedOutputFiles) {
        return new ExecutionResult(
                Outcome.COMPLETED, exitCode, stdout, stderr, outputFiles, oversizedOutputFiles, "");
    }
}
