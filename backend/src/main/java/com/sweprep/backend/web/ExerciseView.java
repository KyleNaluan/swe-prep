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

    static ExerciseView of(Exercise exercise, LanguageAdapter adapter, OptionShuffler shuffler) {
        return new ExerciseView(
                exercise.id(),
                exercise.title(),
                exercise.statement(),
                exercise.domain(),
                exercise.difficulty().name(),
                exercise.form().name(),
                responseView(exercise, adapter, shuffler),
                exercise.hints().stream().map(Hint::name).toList(),
                exercise.explanation() != null);
    }

    private static ResponseView responseView(
            Exercise exercise, LanguageAdapter adapter, OptionShuffler shuffler) {
        return switch (exercise.response()) {
            case Response.Code code ->
                    ResponseView.code(adapter.languageId(), adapter.generateStub(code.signature()));
            // Only the option texts travel to the editor, never the per-distractor
            // misconceptions (issue #42): those are authoring/verification metadata, kept
            // off the wire like the check's explanation and the self-check's model answer.
            // Options are presented in a shuffled but per-attempt-stable order (issue #59)
            // so answer position cannot be exploited; grading matches by text, not index,
            // so the order has no bearing on correctness.
            case Response.Choice choice ->
                    ResponseView.choice(shuffler.order(exercise.id(), choice.optionTexts()));
            // A free-text box renders one of two ways, decided by the grading it is paired
            // with. Paired with an answer key it is the machine-graded "predict the output"
            // rep (issue #18); paired with a self-check it is the self-graded "explain in
            // your own words" produce-then-reveal item (issue #41). The two render very
            // differently, so the kind carries the distinction - but the self-check's model
            // answer is never shipped up front, only revealed after the learner commits.
            case Response.FreeText ignored -> exercise.grading() instanceof Grading.SelfCheck
                    ? ResponseView.selfCheck()
                    : ResponseView.freeText();
        };
    }
}
