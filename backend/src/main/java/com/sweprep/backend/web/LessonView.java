package com.sweprep.backend.web;

import com.sweprep.backend.exercise.Lesson;
import com.sweprep.backend.exercise.SelfExplainPrompt;
import java.util.List;

/**
 * A lesson shaped for the reader (issue #46/#41): the taught body plus its ungraded
 * self-explanation prompts.
 *
 * <p>Unlike a graded check's explanation (issue #51), a prompt's {@code modelAnswer}
 * <em>is</em> shipped here: a lesson is read, not attempted, so there is no verdict to
 * protect by withholding it. The renderer reveals it client-side when the reader chooses,
 * after they have thought about the prompt - the reveal is a reading aid, and nothing about
 * a lesson is recorded or graded.
 *
 * @param id         the lesson id
 * @param title      the lesson title
 * @param statement  the lesson body (Markdown-friendly plain text)
 * @param domain     the domain it belongs to
 * @param difficulty how hard the material is
 * @param prompts    the self-explanation prompts, in order; empty when there are none
 */
public record LessonView(
        String id,
        String title,
        String statement,
        String domain,
        String difficulty,
        List<PromptView> prompts) {

    /** One self-explanation prompt: the question, and the model answer to reveal after. */
    public record PromptView(String prompt, String modelAnswer) {

        static PromptView of(SelfExplainPrompt prompt) {
            return new PromptView(prompt.prompt(), prompt.modelAnswer());
        }
    }

    static LessonView of(Lesson lesson) {
        return new LessonView(
                lesson.id(),
                lesson.title(),
                lesson.statement(),
                lesson.domain(),
                lesson.difficulty().name(),
                lesson.prompts().stream().map(PromptView::of).toList());
    }
}
