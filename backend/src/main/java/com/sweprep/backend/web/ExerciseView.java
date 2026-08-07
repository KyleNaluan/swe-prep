package com.sweprep.backend.web;

import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.Hint;
import com.sweprep.backend.exercise.Response;
import com.sweprep.backend.language.LanguageAdapter;
import java.util.List;

/**
 * Everything the editor needs to render one exercise: the prompt to read, its
 * domain/difficulty/form, a {@link ResponseView} describing how it is answered (a code
 * stub to seed the editor, or a set of options to choose from), and the names of the
 * hint-ladder rungs.
 *
 * <p>Only the rung <em>names</em> travel here, never their bodies: the editor learns
 * how many rungs exist and what each is called so it can offer them, but a rung's text
 * is disclosed only when the solver explicitly takes it (issue #16). That keeps taking
 * a hint an always-chosen, always-recorded act rather than something on the page from
 * the start.
 *
 * @param hints the hint-ladder rung names in order, empty when there are none
 */
public record ExerciseView(
        String id,
        String title,
        String statement,
        String domain,
        String difficulty,
        String form,
        ResponseView response,
        List<String> hints) {

    static ExerciseView of(Exercise exercise, LanguageAdapter adapter) {
        return new ExerciseView(
                exercise.id(),
                exercise.title(),
                exercise.statement(),
                exercise.domain(),
                exercise.difficulty().name(),
                exercise.form().name(),
                responseView(exercise.response(), adapter),
                exercise.hints().stream().map(Hint::name).toList());
    }

    private static ResponseView responseView(Response response, LanguageAdapter adapter) {
        return switch (response) {
            case Response.Code code ->
                    ResponseView.code(adapter.languageId(), adapter.generateStub(code.signature()));
            case Response.Choice choice -> ResponseView.choice(choice.options());
            // Response.FreeText is modeled (design revision t3, T1) but not yet rendered:
            // the produce-then-reveal editor view and self-rating flow are a later ticket
            // (T5). No free-text content exists until then, so this path is unreachable.
            case Response.FreeText ignored -> throw new UnsupportedOperationException(
                    "Free-text responses are not rendered yet (design revision t3, T5)");
        };
    }
}
