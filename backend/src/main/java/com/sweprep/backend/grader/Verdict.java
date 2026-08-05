package com.sweprep.backend.grader;

/**
 * The result of grading a submission.
 *
 * <p>A compile error is a distinct outcome from a test failure: the first means
 * the code never ran, the second means it ran and got the wrong answers. A
 * timeout is distinct again - the code ran too long and was killed.
 *
 * @param outcome what happened
 * @param passed  number of cases that passed (0 unless {@code outcome} is
 *                {@link Outcome#PASSED} or {@link Outcome#FAILED})
 * @param total   number of cases in the exercise
 * @param detail  a human-readable message: compiler diagnostics for a compile
 *                error, a note for a timeout, otherwise empty
 */
public record Verdict(Outcome outcome, int passed, int total, String detail) {

    public enum Outcome {
        /** Every case passed. */
        PASSED,
        /** The code compiled and ran, but not every case passed. */
        FAILED,
        /** The submission did not compile; no case ran. */
        COMPILE_ERROR,
        /** Execution ran past the timeout and was killed (e.g. an infinite loop). */
        TIMEOUT,
        /** The program failed to run for a reason other than the cases themselves. */
        ERROR
    }

    static Verdict of(int passed, int total) {
        Outcome outcome = passed == total ? Outcome.PASSED : Outcome.FAILED;
        return new Verdict(outcome, passed, total, "");
    }

    static Verdict compileError(String detail) {
        return new Verdict(Outcome.COMPILE_ERROR, 0, 0, detail);
    }

    static Verdict timeout(int total, String detail) {
        return new Verdict(Outcome.TIMEOUT, 0, total, detail);
    }

    static Verdict error(String detail) {
        return new Verdict(Outcome.ERROR, 0, 0, detail);
    }
}
