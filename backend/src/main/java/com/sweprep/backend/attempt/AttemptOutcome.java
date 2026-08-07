package com.sweprep.backend.attempt;

/**
 * How a single sitting with an exercise ended.
 *
 * <p>These states keep an abandoned attempt distinct from one never started (no row
 * at all) and from one still open, which is the acceptance criterion issue #15 turns
 * on: abandonment is a real recorded outcome, not an absence. The scheduler (issue
 * #8) collapses this to a quality score - {@link #SOLVED} feeds a high one,
 * {@link #ABANDONED} the lowest.
 *
 * <p>Stored as free text (migration {@code V3__attempts.sql}), so a new outcome is
 * added here alone with no migration - which is exactly how {@link #READ} was added
 * ahead of the lesson track that produces it.
 */
public enum AttemptOutcome {
    /** The sitting is open: practice has started but not yet ended. */
    IN_PROGRESS,
    /** A submission passed; the exercise was solved in this sitting. */
    SOLVED,
    /** The solver gave up without solving it. Recorded, never a mere absence. */
    ABANDONED,
    /**
     * A lesson was read rather than attempted (the second content track's Lesson is
     * read, not solved). It carries no 0-5 quality and the SRS ignores it, but the
     * readiness picture counts it. Recorded from the start so the lesson ticket needs
     * no migration; lessons and their renderer are not built here.
     */
    READ
}
