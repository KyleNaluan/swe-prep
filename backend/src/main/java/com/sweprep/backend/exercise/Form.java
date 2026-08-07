package com.sweprep.backend.exercise;

/**
 * Whether an exercise is a quick recognition <em>rep</em> or a full production
 * <em>challenge</em> (see the daily-session and rep decisions, issues #3 and #9).
 *
 * <p>Form is deliberately an <em>attribute</em> of an {@link Exercise}, not a
 * separate type in a class hierarchy: a rep and a challenge share the same model,
 * the same loading path and the same grading seam, and differ only in this tag
 * and in how much of the session they fill. Making it a subtype would fork all of
 * that for no gain (see the exercise abstraction decision, issue #6).
 */
public enum Form {
    /** A short, ~recognition-level drill; several derive from each challenge. */
    REP,
    /** A full solve-it-from-scratch problem. */
    CHALLENGE
}
