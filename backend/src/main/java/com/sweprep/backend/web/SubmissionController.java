package com.sweprep.backend.web;

import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.ExerciseCatalog;
import com.sweprep.backend.grader.Grader;
import com.sweprep.backend.grader.Verdict;
import com.sweprep.backend.language.LanguageAdapter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The editor's two endpoints: fetch the current exercise, and run a submission
 * against it. This is the wire the tracer bullet proves end to end - code typed
 * in the browser arrives here, is compiled and run on the backend against the
 * language-neutral cases, and a verdict goes back.
 */
@RestController
@RequestMapping("/api/exercise")
public class SubmissionController {

    private final ExerciseCatalog catalog;
    private final LanguageAdapter adapter;
    private final Grader grader;

    public SubmissionController(ExerciseCatalog catalog, LanguageAdapter adapter, Grader grader) {
        this.catalog = catalog;
        this.adapter = adapter;
        this.grader = grader;
    }

    @GetMapping
    public ExerciseView current() {
        return ExerciseView.of(catalog.current(), adapter);
    }

    @PostMapping("/run")
    public RunResponse run(@RequestBody RunRequest request) {
        Exercise exercise = catalog.current();
        Verdict verdict = grader.grade(exercise, request.code());
        return RunResponse.of(verdict);
    }
}
