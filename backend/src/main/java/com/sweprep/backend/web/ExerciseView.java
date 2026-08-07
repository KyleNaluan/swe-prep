package com.sweprep.backend.web;

import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.Grading;
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
 * <p>The check's explanation follows the same withholding discipline (issue #51): only
 * {@code hasExplanation} travels up front - whether one exists, so the editor knows
 * whether to offer the "why" button - never the text. The explanation is disclosed
 * automatically on a wrong answer (in the submission's response) or on request when
 * correct, so shipping it here would defeat both by letting the solver read it before
 * answering.
 *
 * @param hints          the hint-ladder rung names in order, empty when there are none
 * @param hasExplanation whether the check carries an explanation to disclose
 */
public record ExerciseView(
        String id,
        String title,
        String statement,
        String domain,
        String difficulty,
        String form,
        ResponseView response,
        List<String> hints,
        boolean hasExplanation) {

    static ExerciseView of(Exercise exercise, LanguageAdapter adapter) {
        return new ExerciseView(
                exercise.id(),
                exercise.title(),
                exercise.statement(),
                exercise.domain(),
                exercise.difficulty().name(),
                exercise.form().name(),
                responseView(exercise, adapter),
                exercise.hints().stream().map(Hint::name).toList(),
                exercise.explanation() != null);
    }

    private static ResponseView responseView(Exercise exercise, LanguageAdapter adapter) {
        return switch (exercise.response()) {
            case Response.Code code ->
                    ResponseView.code(adapter.languageId(), adapter.generateStub(code.signature()));
            case Response.Choice choice -> ResponseView.choice(choice.options());
            // A free-text box renders for a "predict the output" rep (issue #18), whose
            // typed value is machine-graded against an answer key. A self-check free-text
            // item shares the response type but its produce-then-reveal editor view and
            // self-rating flow are a later ticket (T5), so that pairing stays unrendered.
            case Response.FreeText ignored -> exercise.grading() instanceof Grading.SelfCheck
                    ? throwSelfCheckNotRendered()
                    : ResponseView.freeText();
        };
    }

    private static ResponseView throwSelfCheckNotRendered() {
        throw new UnsupportedOperationException(
                "Self-check free-text responses are not rendered yet (design revision t3, T5)");
    }
}
