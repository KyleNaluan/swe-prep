package com.sweprep.backend.web;

import com.sweprep.backend.content.ContentException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns a {@link ContentException} - the content path is missing, unreadable, or a
 * file is malformed - into a 500 whose body carries the plain-language cause, so
 * the editor can show the solver a clear error instead of a bare status code. This
 * is the "reports a clear error when it is missing or malformed" half of issue
 * #14's first acceptance criterion.
 */
@RestControllerAdvice
public class ContentErrorHandler {

    /** A single-field error body the front end can read and display. */
    public record ApiError(String error) {}

    @ExceptionHandler(ContentException.class)
    public ResponseEntity<ApiError> handleContent(ContentException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError(e.getMessage()));
    }
}
