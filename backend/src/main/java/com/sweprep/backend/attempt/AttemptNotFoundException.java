package com.sweprep.backend.attempt;

/** No attempt (or exercise to attempt) with the given id exists. Maps to a 404. */
public class AttemptNotFoundException extends RuntimeException {

    public AttemptNotFoundException(String message) {
        super(message);
    }
}
