package com.sweprep.backend.web;

import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.ExerciseCatalog;
import com.sweprep.backend.grader.GraderRegistry;
import com.sweprep.backend.grader.Verdict;
import com.sweprep.backend.language.LanguageAdapter;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The editor's endpoints over the real content set: list the exercises, fetch one
 * to render, and grade an answer to it. Code typed (or an option picked) in the
 * browser arrives here, is routed to the grader that handles the exercise's
 * grading spec - which for a coding problem compiles and runs it, and for a
 * concept question does not - and a verdict goes back.
 */
@RestController
@RequestMapping("/api/exercises")
public class ExerciseController {

    private final ExerciseCatalog catalog;
    private final LanguageAdapter adapter;
    private final GraderRegistry graders;

    public ExerciseController(
            ExerciseCatalog catalog, LanguageAdapter adapter, GraderRegistry graders) {
        this.catalog = catalog;
        this.adapter = adapter;
        this.graders = graders;
    }

    @GetMapping
    public List<ExerciseSummary> list() {
        return catalog.all().stream().map(ExerciseSummary::of).toList();
    }

    @GetMapping("/{id}")
    public ExerciseView get(@PathVariable String id) {
        return ExerciseView.of(require(id), adapter);
    }

    @PostMapping("/{id}/run")
    public RunResponse run(@PathVariable String id, @RequestBody RunRequest request) {
        Verdict verdict = graders.grade(require(id), request.submission());
        return RunResponse.of(verdict);
    }

    private Exercise require(String id) {
        return catalog.byId(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No exercise with id '" + id + "'"));
    }
}
