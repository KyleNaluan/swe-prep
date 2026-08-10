package com.sweprep.backend.web;

import com.sweprep.backend.language.LanguageAdapterRegistry;
import com.sweprep.backend.web.ContentErrorHandler.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns a request naming a language no adapter targets (issue #26) into a 400 whose
 * body carries the plain-language cause - the same single-field {@link ApiError}
 * shape every other error handler in this package uses.
 */
@RestControllerAdvice
public class LanguageErrorHandler {

    @ExceptionHandler(LanguageAdapterRegistry.UnsupportedLanguageException.class)
    public ResponseEntity<ApiError> handleUnsupportedLanguage(
            LanguageAdapterRegistry.UnsupportedLanguageException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError(e.getMessage()));
    }
}
