package com.sweprep.backend.content;

/**
 * Thrown when the exercise content cannot be loaded: the configured content path
 * is missing or unreadable, or a content file is malformed. Its message names the
 * problem plainly (which file, which field) so the app can report a clear error
 * rather than a stack trace, which is an acceptance criterion of issue #14.
 */
public class ContentException extends RuntimeException {

    public ContentException(String message) {
        super(message);
    }

    public ContentException(String message, Throwable cause) {
        super(message, cause);
    }
}
