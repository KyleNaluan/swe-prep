package com.sweprep.backend.sql;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The raw outcome of one {@link SqlRunner} execution - what happened at the level of the
 * query, leaving interpreting it into a pass/fail verdict to {@code SqlQueryGrader}, the
 * same runner/grader split the language seam keeps.
 *
 * @param outcome      what happened
 * @param rows         the result set as a JSON array of rows, each row itself a JSON array
 *                      of column values in position, when {@code outcome} is
 *                      {@link Outcome#COMPLETED}; {@code null} otherwise
 * @param errorMessage the database's own error text - a syntax error, or a write refused by
 *                      the read-only role or transaction (issue #25's "refused, not merely
 *                      undone") - when {@code outcome} is {@link Outcome#QUERY_ERROR} or
 *                      {@link Outcome#TIMEOUT}; empty otherwise
 */
public record SqlExecutionResult(Outcome outcome, JsonNode rows, String errorMessage) {

    public enum Outcome {
        /** The query ran to completion and returned a result set. */
        COMPLETED,
        /**
         * The query was invalid, or was refused - a write or schema change attempted
         * against the read-only role or the read-only transaction (issue #25).
         */
        QUERY_ERROR,
        /** The query ran past its timeout and was cancelled. */
        TIMEOUT
    }

    // Public, not package-private: unlike Verdict's factories (only ever built inside
    // grader), a SqlRunner is a seam other packages implement - a fake in a grader test,
    // or a future alternative runner - and need a clean way to build its result with.

    public static SqlExecutionResult completed(JsonNode rows) {
        return new SqlExecutionResult(Outcome.COMPLETED, rows, "");
    }

    public static SqlExecutionResult queryError(String message) {
        return new SqlExecutionResult(Outcome.QUERY_ERROR, null, message);
    }

    public static SqlExecutionResult timeout(String message) {
        return new SqlExecutionResult(Outcome.TIMEOUT, null, message);
    }
}
