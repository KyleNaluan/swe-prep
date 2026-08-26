package com.sweprep.backend.exercise;

/**
 * One worked example on a content item, LeetCode-style (the "captain-requested
 * improvement" that put algorithm problems' description pane on par with a real
 * interview site): a display-formatted input, the output it produces, and an
 * optional one-or-two-sentence walk-through of why.
 *
 * <p>{@link #input()} and {@link #output()} are plain display strings, not the raw
 * JSON a {@link TestCase} carries - they are formatted per the exercise's own
 * signature parameter names (e.g. {@code "nums = [2,7,11,15], target = 9"}), the
 * way LeetCode names arguments. This is deliberately a display-only, authored
 * projection of a real {@code TestCase}, not a second execution path: nothing here
 * is ever run or graded, so an example can only ever <em>disagree</em> with what
 * Run grades if an author lets it drift - the content-authoring discipline (not a
 * type-level guarantee) is to derive every example directly from an item's actual
 * {@code grading.cases}, never invent one.
 *
 * @param input       the display-formatted arguments, e.g. {@code "nums = [2,7,11,15], target = 9"}
 * @param output      the display-formatted expected result, e.g. {@code "[0,1]"}
 * @param explanation an optional short note on why the input maps to the output;
 *                    {@code null} when the example is self-explanatory
 */
public record Example(String input, String output, String explanation) {}
