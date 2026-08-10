package com.sweprep.backend.sql;

/**
 * Thrown when the SQL fixture infrastructure itself cannot be prepared - the fixture
 * database or reader role could not be provisioned, or the fixture's schema could not be
 * loaded or connected to. Distinct from a submission being refused or timing out (issue
 * #25's {@code SqlExecutionResult} outcomes), which are ordinary grading results, not
 * exceptions: this is an infrastructure failure, reported as a clear 500 by {@link
 * SqlErrorHandler}, the same shape {@code ContentException} gives a broken content clone.
 */
public class SqlFixtureException extends RuntimeException {

    public SqlFixtureException(String message) {
        super(message);
    }

    public SqlFixtureException(String message, Throwable cause) {
        super(message, cause);
    }
}
