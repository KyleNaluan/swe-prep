package com.sweprep.backend.exercise;

/**
 * The three standout-box styles a {@link LessonBlock.Callout} can carry (issue #90
 * follow-on visual redesign), matching the W3Schools/GfG convention of a colored,
 * bordered aside distinct from ordinary prose: something worth noting in passing, an
 * optional tip that deepens understanding without being required, or a warning about a
 * common mistake. The renderer picks the callout's accent (background/border color)
 * from this value against the app's own Direction C design tokens - never a
 * library-supplied theme.
 */
public enum CalloutKind {
    NOTE,
    TIP,
    WARNING
}
