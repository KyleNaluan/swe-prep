package com.sweprep.backend.commit;

/**
 * What {@link SolutionCommitService#commitSolution} did. Always returned, never
 * thrown - a commit failure (a missing clone, a network-down push, an unchanged
 * solution) degrades to "not committed" rather than failing the submission that
 * triggered it, the same resilience pattern {@code FileExerciseCatalog} uses for a
 * missing content clone.
 *
 * @param committed whether a real commit was made (and, if {@code push} is enabled,
 *                  pushed)
 * @param reason    why nothing was committed, or a non-fatal note (e.g. "committed
 *                  locally; push failed"); {@code null} on a clean commit
 */
public record SolutionCommitResult(boolean committed, String reason) {

    static SolutionCommitResult ofCommitted() {
        return new SolutionCommitResult(true, null);
    }

    static SolutionCommitResult ofCommittedButPushFailed(String reason) {
        return new SolutionCommitResult(true, reason);
    }

    static SolutionCommitResult ofSkipped(String reason) {
        return new SolutionCommitResult(false, reason);
    }
}
