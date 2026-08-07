package com.sweprep.backend.attempt;

/**
 * An action was asked of an attempt that its current state does not allow - such as
 * submitting to, abandoning, or revealing on an attempt that has already ended. Maps
 * to a 409 Conflict.
 */
public class IllegalAttemptStateException extends RuntimeException {

    public IllegalAttemptStateException(String message) {
        super(message);
    }
}
