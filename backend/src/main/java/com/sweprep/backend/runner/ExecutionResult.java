package com.sweprep.backend.runner;

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
 * @param compilerMessage compiler diagnostics when {@code outcome} is
 *                        {@link Outcome#COMPILE_ERROR}, otherwise empty
 */
public record ExecutionResult(
        Outcome outcome,
        int exitCode,
        String stdout,
        String stderr,
        String compilerMessage) {

    public enum Outcome {
        /** The sources compiled and the program ran to completion. */
        COMPLETED,
        /** The sources did not compile; the program never ran. */
        COMPILE_ERROR,
        /** The program ran past its timeout and was killed. */
        TIMEOUT
    }

    static ExecutionResult compileError(String message) {
        return new ExecutionResult(Outcome.COMPILE_ERROR, -1, "", "", message);
    }

    static ExecutionResult timeout(String stdout, String stderr) {
        return new ExecutionResult(Outcome.TIMEOUT, -1, stdout, stderr, "");
    }

    static ExecutionResult completed(int exitCode, String stdout, String stderr) {
        return new ExecutionResult(Outcome.COMPLETED, exitCode, stdout, stderr, "");
    }
}
