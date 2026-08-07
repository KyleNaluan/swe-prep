package com.sweprep.backend.web;

import com.sweprep.backend.attempt.AttemptNotFoundException;
import com.sweprep.backend.attempt.IllegalAttemptStateException;
import com.sweprep.backend.web.ContentErrorHandler.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps the attempt lifecycle's failures to HTTP: an unknown attempt or exercise is a
 * 404, and an action the attempt's state forbids (submitting to or abandoning an
 * already-ended attempt) is a 409 Conflict. Both carry the same single-field
 * {@link ApiError} body the editor already reads, so the message reaches the solver.
 */
@RestControllerAdvice
public class AttemptErrorHandler {

    @ExceptionHandler(AttemptNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(AttemptNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(e.getMessage()));
    }

    @ExceptionHandler(IllegalAttemptStateException.class)
    public ResponseEntity<ApiError> handleConflict(IllegalAttemptStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(e.getMessage()));
    }
}
