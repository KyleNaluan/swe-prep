package com.sweprep.backend.attempt;

/**
 * A request to an attempt was malformed - such as revealing a non-self-check item, or
 * revealing before producing any text. Maps to a 400 Bad Request. Distinct from a broad
 * {@link IllegalArgumentException} so that only these explicit validation failures reach
 * the client as a 400; an unexpected {@code IllegalArgumentException} (e.g. a bad enum
 * value read back from the database) stays a 500.
 */
public class InvalidAttemptRequestException extends RuntimeException {

    public InvalidAttemptRequestException(String message) {
        super(message);
    }
}
