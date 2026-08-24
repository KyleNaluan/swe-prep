package com.sweprep.backend.exercise;

import java.util.List;

/**
 * One structured piece of a {@link Lesson#body()} (issue #90 follow-on: the visual
 * redesign captain feedback that a lesson read as "one block of continuous text").
 * Before this, a lesson's whole taught content was exactly one field - {@code
 * statement}, plain text rendered as a single paragraph - which is why it read as an
 * undifferentiated wall of text no matter how the page around it was styled. {@link
 * #body()} is the structured alternative: an ordered list of blocks a lesson author
 * composes to get W3Schools/GfG-like rhythm - heading, short prose, a standout
 * example, repeat - with the renderer, not the author, responsible for how each
 * block looks.
 *
 * <p>The set of block kinds is deliberately a sealed hierarchy, the same shape
 * {@link Comparison} and {@link Response}/{@link Grading} already use in this
 * package: a new block kind is added later by adding one more permitted
 * implementation, never by redesigning the model or special-casing a renderer.
 *
 * <p><b>Legacy compatibility is structural, not a fallback flag.</b> {@link
 * Lesson#body()} is simply empty for any lesson authored only with {@code statement}
 * (every lesson in the content set today) - {@code LessonParser} never invents blocks
 * from a plain-text statement, and the renderer (frontend {@code Lesson.tsx}) falls
 * back to rendering {@code statement} as a single paragraph exactly as it always has
 * when {@code body} is empty. A lesson is never required to carry structured blocks;
 * restructuring is a content-repo follow-up (this ticket's own acceptance criterion
 * #5), not something this change does to existing content.
 *
 * <p>Inline code (a backtick-delimited span inside prose) is deliberately <em>not</em>
 * its own block kind - it is markdown-style inline syntax inside a {@link
 * Paragraph#text()}, a {@link ListBlock} item, a {@link Table} cell or a {@link
 * Callout#text()}, parsed by the renderer at render time. A separate block kind would
 * force an author to break a sentence into multiple blocks just to mention a
 * variable name inline, which defeats the "short prose" rhythm this format exists
 * to enable.
 */
public sealed interface LessonBlock
        permits LessonBlock.Heading,
                LessonBlock.Paragraph,
                LessonBlock.Example,
                LessonBlock.Callout,
                LessonBlock.ListBlock,
                LessonBlock.Table {

    /**
     * A section heading inside the lesson body. {@code level} is 2 or 3 (an h2 or h3)
     * since the lesson's own title already renders as the page's h1 - a block-level
     * heading can never claim that outermost rank.
     */
    record Heading(int level, String text) implements LessonBlock {
        public Heading {
            if (level != 2 && level != 3) {
                throw new IllegalArgumentException("heading level must be 2 or 3, was " + level);
            }
            requireNonBlank(text, "heading text");
        }
    }

    /** A short paragraph of prose. May contain inline `code` spans. */
    record Paragraph(String text) implements LessonBlock {
        public Paragraph {
            requireNonBlank(text, "paragraph text");
        }
    }

    /**
     * A standout code example - the block the visual redesign asked to make examples
     * "stand out" (requirement 3): rendered with its own background/border accent,
     * never blended into surrounding prose. {@code language} drives syntax
     * highlighting (see {@code CodeHighlighter} on the frontend); {@code caption} and
     * {@code output} are both optional - a caption names what the example shows, an
     * output shows what it prints/returns, and either, both or neither may be present.
     */
    record Example(String language, String code, String caption, String output) implements LessonBlock {
        public Example {
            requireNonBlank(language, "example language");
            requireNonBlank(code, "example code");
            caption = blankToNull(caption);
            output = blankToNull(output);
        }
    }

    /**
     * A note/tip/warning callout - a short aside visually distinct from ordinary
     * prose (background/border accent keyed off {@link #style()}), the W3Schools/GfG
     * "Note:"/"Tip:" box convention.
     */
    record Callout(CalloutKind style, String text) implements LessonBlock {
        public Callout {
            if (style == null) {
                throw new IllegalArgumentException("callout style must not be null");
            }
            requireNonBlank(text, "callout text");
        }
    }

    /**
     * A simple bullet or numbered list. Named {@code ListBlock} rather than {@code
     * List} to avoid colliding with {@code java.util.List}, which every block's own
     * fields already use. Each item may contain inline `code` spans, same as a
     * paragraph.
     */
    record ListBlock(boolean ordered, List<String> items) implements LessonBlock {
        public ListBlock {
            if (items == null || items.isEmpty()) {
                throw new IllegalArgumentException("a list block must have at least one item");
            }
            items.forEach(item -> requireNonBlank(item, "list item"));
            items = List.copyOf(items);
        }
    }

    /**
     * A simple data table: a header row plus zero or more data rows, each the same
     * width as the header. A cell may contain inline `code` spans, same as a
     * paragraph. There is no notion of column type or alignment - the format stays
     * as simple as the "simple lists/tables" requirement asks for; a richer table
     * shape is a later block kind, not a field bolted onto this one.
     */
    record Table(List<String> headers, List<List<String>> rows) implements LessonBlock {
        public Table {
            if (headers == null || headers.isEmpty()) {
                throw new IllegalArgumentException("a table must have at least one header");
            }
            headers.forEach(header -> requireNonBlank(header, "table header"));
            headers = List.copyOf(headers);
            rows = rows == null ? List.of() : rows.stream().map(List::copyOf).toList();
            for (List<String> row : rows) {
                if (row.size() != headers.size()) {
                    throw new IllegalArgumentException(
                            "every table row must have " + headers.size()
                                    + " cells (one per header), found " + row.size());
                }
            }
        }
    }

    private static void requireNonBlank(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(what + " must not be blank");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
