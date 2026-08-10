package com.sweprep.backend.web;

import com.sweprep.backend.sql.SqlFixtureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns a {@link SqlFixtureException} - the fixture database or reader role could not be
 * provisioned, or a fixture's schema could not be loaded - into a 500 whose body carries
 * the plain-language cause, the same shape {@link ContentErrorHandler} gives a broken
 * content clone. This is infrastructure failing to come up, not a submission being refused
 * or timing out - those are ordinary grading outcomes, never an exception.
 */
@RestControllerAdvice
public class SqlErrorHandler {

    @ExceptionHandler(SqlFixtureException.class)
    public ResponseEntity<ContentErrorHandler.ApiError> handleSqlFixture(SqlFixtureException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ContentErrorHandler.ApiError(e.getMessage()));
    }
}
