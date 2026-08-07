package com.sweprep.backend.web;

import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.Response;
import com.sweprep.backend.language.LanguageAdapter;

/**
 * Everything the editor needs to render one exercise: the prompt to read, its
 * domain/difficulty/form, and a {@link ResponseView} describing how it is answered
 * (a code stub to seed the editor, or a set of options to choose from).
 */
public record ExerciseView(
        String id,
        String title,
        String statement,
        String domain,
        String difficulty,
        String form,
        ResponseView response) {

    static ExerciseView of(Exercise exercise, LanguageAdapter adapter) {
        return new ExerciseView(
                exercise.id(),
                exercise.title(),
                exercise.statement(),
                exercise.domain(),
                exercise.difficulty().name(),
                exercise.form().name(),
                responseView(exercise.response(), adapter));
    }

    private static ResponseView responseView(Response response, LanguageAdapter adapter) {
        return switch (response) {
            case Response.Code code ->
                    ResponseView.code(adapter.languageId(), adapter.generateStub(code.signature()));
            case Response.Choice choice -> ResponseView.choice(choice.options());
        };
    }
}
