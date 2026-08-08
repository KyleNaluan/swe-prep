package com.sweprep.backend.web;

import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.ExerciseCatalog;
import com.sweprep.backend.language.LanguageAdapter;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The editor's content endpoints over the real exercise set: list the exercises and
 * fetch one to render. Grading no longer lives here - every press of Run is a
 * submission within an attempt (issue #15), so it is posted to {@link AttemptController}
 * and recorded, rather than graded statelessly and forgotten.
 */
@RestController
@RequestMapping("/api/exercises")
public class ExerciseController {

    private final ExerciseCatalog catalog;
    private final LanguageAdapter adapter;
    private final OptionShuffler shuffler;

    public ExerciseController(
            ExerciseCatalog catalog, LanguageAdapter adapter, OptionShuffler shuffler) {
        this.catalog = catalog;
        this.adapter = adapter;
        this.shuffler = shuffler;
    }

    @GetMapping
    public List<ExerciseSummary> list() {
        return catalog.all().stream().map(ExerciseSummary::of).toList();
    }

    @GetMapping("/{id}")
    public ExerciseView get(@PathVariable String id) {
        return ExerciseView.of(require(id), adapter, shuffler);
    }

    private Exercise require(String id) {
        return catalog.byId(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No exercise with id '" + id + "'"));
    }
}
