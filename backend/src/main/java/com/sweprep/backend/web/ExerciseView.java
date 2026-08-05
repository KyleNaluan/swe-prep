package com.sweprep.backend.web;

import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.language.LanguageAdapter;

/**
 * What the editor needs to render one exercise: the statement to read, the
 * language it is solved in, and the compiling stub to seed the editor with.
 */
public record ExerciseView(
        String id, String title, String statement, String language, String stub) {

    static ExerciseView of(Exercise exercise, LanguageAdapter adapter) {
        return new ExerciseView(
                exercise.id(),
                exercise.title(),
                exercise.statement(),
                adapter.languageId(),
                adapter.generateStub(exercise.signature()));
    }
}
