package com.sweprep.backend.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sweprep.backend.exercise.Lesson;
import com.sweprep.backend.exercise.LessonBlock;
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
 * @param statement  the lesson body (Markdown-friendly plain text); still sent even when
 *                   {@code body} is non-empty, since a caller other than the lesson
 *                   renderer (e.g. a future search/preview surface) may still want the
 *                   plain-text form
 * @param domain     the domain it belongs to
 * @param difficulty how hard the material is
 * @param body       the structured body blocks (issue #90 follow-on), in reading order;
 *                   empty for a legacy lesson, in which case the renderer falls back to
 *                   {@code statement}
 * @param prompts    the self-explanation prompts, in order; empty when there are none
 */
public record LessonView(
        String id,
        String title,
        String statement,
        String domain,
        String difficulty,
        List<BlockView> body,
        List<PromptView> prompts) {

    /** One self-explanation prompt: the question, and the model answer to reveal after. */
    public record PromptView(String prompt, String modelAnswer) {

        static PromptView of(SelfExplainPrompt prompt) {
            return new PromptView(prompt.prompt(), prompt.modelAnswer());
        }
    }

    /**
     * One {@link LessonBlock}, flattened into a single shape the frontend can switch on
     * by {@code kind} - the same discriminated-union DTO shape {@link ResponseView}
     * already uses for a sealed hierarchy, fields not meaningful for a given {@code kind}
     * omitted from the JSON via {@link JsonInclude}. The frontend's own discriminated
     * union type narrows on {@code kind} exactly as the backend does.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BlockView(
            String kind,
            Integer level,
            String text,
            String language,
            String code,
            String caption,
            String output,
            String style,
            Boolean ordered,
            List<String> items,
            List<String> headers,
            List<List<String>> rows) {

        static BlockView heading(int level, String text) {
            return new BlockView("heading", level, text, null, null, null, null, null, null, null, null, null);
        }

        static BlockView paragraph(String text) {
            return new BlockView("paragraph", null, text, null, null, null, null, null, null, null, null, null);
        }

        static BlockView example(String language, String code, String caption, String output) {
            return new BlockView(
                    "example", null, null, language, code, caption, output, null, null, null, null, null);
        }

        static BlockView callout(String style, String text) {
            return new BlockView("callout", null, text, null, null, null, null, style, null, null, null, null);
        }

        static BlockView list(boolean ordered, List<String> items) {
            return new BlockView("list", null, null, null, null, null, null, null, ordered, items, null, null);
        }

        static BlockView table(List<String> headers, List<List<String>> rows) {
            return new BlockView("table", null, null, null, null, null, null, null, null, null, headers, rows);
        }

        static BlockView of(LessonBlock block) {
            return switch (block) {
                case LessonBlock.Heading h -> heading(h.level(), h.text());
                case LessonBlock.Paragraph p -> paragraph(p.text());
                case LessonBlock.Example e -> example(e.language(), e.code(), e.caption(), e.output());
                case LessonBlock.Callout c -> callout(c.style().name(), c.text());
                case LessonBlock.ListBlock l -> list(l.ordered(), l.items());
                case LessonBlock.Table t -> table(t.headers(), t.rows());
            };
        }
    }

    static LessonView of(Lesson lesson) {
        return new LessonView(
                lesson.id(),
                lesson.title(),
                lesson.statement(),
                lesson.domain(),
                lesson.difficulty().name(),
                lesson.body().stream().map(BlockView::of).toList(),
                lesson.prompts().stream().map(PromptView::of).toList());
    }
}
