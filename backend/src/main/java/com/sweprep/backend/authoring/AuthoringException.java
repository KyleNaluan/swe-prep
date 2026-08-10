package com.sweprep.backend.authoring;

/**
 * Thrown when the content-entry tool cannot proceed: a malformed problem spec, a
 * reference solution that fails its own test cases, a target directory the safety
 * guard refuses to write to, or a derivation step that cannot produce a confident
 * result. The message always names the specific cause, matching the loader's own
 * {@code ContentException} convention (issue #14) - this tool is authoring-time,
 * not runtime, but a vague failure is exactly as unhelpful in either place.
 */
public class AuthoringException extends RuntimeException {

    public AuthoringException(String message) {
        super(message);
    }

    public AuthoringException(String message, Throwable cause) {
        super(message, cause);
    }
}
