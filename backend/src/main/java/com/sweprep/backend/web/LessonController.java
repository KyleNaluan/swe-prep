package com.sweprep.backend.web;

import com.sweprep.backend.exercise.Content;
import com.sweprep.backend.exercise.ContentCatalog;
import com.sweprep.backend.exercise.Lesson;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The reader's content endpoints over the loaded lessons (issue #46/#41): list the lessons
 * and fetch one to read, including its ungraded self-explanation prompts.
 *
 * <p>A lesson is <strong>read, never attempted</strong>, so there is deliberately no grade
 * or submission path here - reading is not a sitting, and the self-explanation prompts are a
 * client-side reading aid (issue #41), not a graded step. It reads over the wider {@link
 * ContentCatalog} (which sees lessons) rather than the exercise-only {@link
 * com.sweprep.backend.exercise.ExerciseCatalog}, so a lesson never reaches the grade path.
 */
@RestController
@RequestMapping("/api/lessons")
public class LessonController {

    private final ContentCatalog catalog;

    public LessonController(ContentCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public List<LessonSummary> list() {
        return catalog.allContent().stream()
                .filter(Lesson.class::isInstance)
                .map(Lesson.class::cast)
                .map(LessonSummary::of)
                .toList();
    }

    @GetMapping("/{id}")
    public LessonView get(@PathVariable String id) {
        Content content = catalog
                .contentById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No lesson with id '" + id + "'"));
        if (!(content instanceof Lesson lesson)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Content '" + id + "' is not a lesson");
        }
        return LessonView.of(lesson);
    }
}
