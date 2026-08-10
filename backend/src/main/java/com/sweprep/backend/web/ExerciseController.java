package com.sweprep.backend.web;

import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.ExerciseCatalog;
import com.sweprep.backend.language.LanguageAdapter;
import com.sweprep.backend.language.LanguageAdapterRegistry;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The editor's content endpoints over the real exercise set: list the exercises and
 * fetch one to render. Grading no longer lives here - every press of Run is a
 * submission within an attempt (issue #15), so it is posted to {@link AttemptController}
 * and recorded, rather than graded statelessly and forgotten.
 *
 * <p>A code exercise's stub is generated in the requested {@code language} (issue #26:
 * the user can choose which language to solve in), resolved through {@link
 * LanguageAdapterRegistry} and defaulting to Java when omitted. The choice is entirely
 * per-request - nothing about it is stored - so switching languages is just fetching
 * the exercise again with a different query parameter.
 */
@RestController
@RequestMapping("/api/exercises")
public class ExerciseController {

    private final ExerciseCatalog catalog;
    private final LanguageAdapterRegistry adapters;
    private final OptionShuffler shuffler;

    public ExerciseController(
            ExerciseCatalog catalog, LanguageAdapterRegistry adapters, OptionShuffler shuffler) {
        this.catalog = catalog;
        this.adapters = adapters;
        this.shuffler = shuffler;
    }

    @GetMapping
    public List<ExerciseSummary> list() {
        return catalog.all().stream().map(ExerciseSummary::of).toList();
    }

    @GetMapping("/{id}")
    public ExerciseView get(
            @PathVariable String id,
            @RequestParam(name = "language", required = false) String language) {
        // An unknown language throws LanguageAdapterRegistry.UnsupportedLanguageException,
        // mapped to a 400 by LanguageErrorHandler - not caught here, so every caller of
        // forLanguage(...) reports it the same way.
        LanguageAdapter adapter = adapters.forLanguage(language);
        return ExerciseView.of(require(id), adapter, shuffler);
    }

    private Exercise require(String id) {
        return catalog.byId(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No exercise with id '" + id + "'"));
    }
}
