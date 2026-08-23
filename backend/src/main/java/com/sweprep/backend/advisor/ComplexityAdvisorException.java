package com.sweprep.backend.advisor;

/**
 * Thrown when the model second opinion (issue #83) could not be produced: the
 * Anthropic call failed (network, rate limit, non-2xx) or its answer could not be
 * read as a {@link ModelComplexityReading}. Distinct from an ordinary disagreement -
 * a disagreement is a successful, informative result; this is the call not coming
 * back with one at all. Mapped to a 502 by {@link
 * com.sweprep.backend.web.ComplexityAdvisorErrorHandler} - infrastructure failing to
 * answer, the same shape {@link com.sweprep.backend.sql.SqlFixtureException} gives a
 * broken fixture database, never a 500 that reads as a bug in this app.
 */
public class ComplexityAdvisorException extends RuntimeException {

    public ComplexityAdvisorException(String message, Throwable cause) {
        super(message, cause);
    }

    public ComplexityAdvisorException(String message) {
        super(message);
    }
}
