package com.sweprep.backend.web;

import com.sweprep.backend.advisor.ComplexityAdvisorException;
import com.sweprep.backend.web.ContentErrorHandler.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns a {@link ComplexityAdvisorException} - the model call for a second opinion
 * (issue #83) failed, or its answer could not be read - into a 502 whose body
 * carries the plain-language cause, the same single-field {@link ApiError} shape
 * every other error handler in this package uses. Distinct from {@link
 * AttemptErrorHandler}'s 400/404/409s: this is an upstream service failing to
 * answer, not the attempt's own state forbidding the action - and distinct from a
 * disagreement, which is an ordinary, informative result, never an exception.
 */
@RestControllerAdvice
public class ComplexityAdvisorErrorHandler {

    @ExceptionHandler(ComplexityAdvisorException.class)
    public ResponseEntity<ApiError> handleAdvisorFailure(ComplexityAdvisorException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ApiError(e.getMessage()));
    }
}
