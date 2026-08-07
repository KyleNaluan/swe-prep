package com.sweprep.backend.grader;

/**
 * The result of grading a submission.
 *
 * <p>A compile error is a distinct outcome from a test failure: the first means
 * the code never ran, the second means it ran and got the wrong answers. A
 * timeout is distinct again - the code ran too long and was killed.
 *
 * <p>{@link #runtimeMillis} is reported for interest only and is never part of the
 * verdict: correctness is pass or fail, and how long a submission ran does not change
 * it (issue #16/#5). It is 0 for a verdict that ran no code (an answer key).
 *
 * @param outcome       what happened
 * @param passed        number of cases that passed (0 unless {@code outcome} is
 *                      {@link Outcome#PASSED} or {@link Outcome#FAILED})
 * @param total         number of cases in the exercise
 * @param detail        a human-readable message: compiler diagnostics for a compile
 *                      error, a note for a timeout, otherwise empty
 * @param runtimeMillis wall-clock time the execution took, for display only
 */
public record Verdict(Outcome outcome, int passed, int total, String detail, long runtimeMillis) {

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
        return new Verdict(outcome, passed, total, "", 0);
    }

    static Verdict compileError(String detail) {
        return new Verdict(Outcome.COMPILE_ERROR, 0, 0, detail, 0);
    }

    static Verdict timeout(int total, String detail) {
        return new Verdict(Outcome.TIMEOUT, 0, total, detail, 0);
    }

    static Verdict error(String detail) {
        return new Verdict(Outcome.ERROR, 0, 0, detail, 0);
    }

    /** The same verdict with its execution time attached, for display only. */
    Verdict withRuntime(long runtimeMillis) {
        return new Verdict(outcome, passed, total, detail, runtimeMillis);
    }
}
