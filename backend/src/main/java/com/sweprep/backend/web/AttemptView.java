package com.sweprep.backend.web;

import com.sweprep.backend.attempt.Attempt;
import com.sweprep.backend.attempt.AttemptWithCount;
import java.time.Instant;

/**
 * An attempt shaped for the editor's history list and lifecycle responses. It
 * carries the full record - including the fields whose producing features are not
 * built yet (hints, complexity) - so the UI and later scheduler work read one shape.
 *
 * @param id                     the attempt id
 * @param exerciseId             the content exercise id
 * @param exerciseTitle          the snapshotted exercise title
 * @param domain                 the snapshotted domain
 * @param form                   {@code REP} or {@code CHALLENGE}
 * @param outcome                {@code IN_PROGRESS}, {@code SOLVED} or {@code ABANDONED}
 * @param startedAt              when the sitting began
 * @param endedAt                when it ended, or {@code null} while in progress
 * @param submissionCount        how many times Run was pressed
 * @param hintsTaken             hint-ladder rungs climbed (issue #16)
 * @param failingCaseRevealed    whether the failing case was revealed (issue #5/#16)
 * @param revealHypothesis       the one-line guess typed before the reveal, or null
 * @param explanationRequested   whether the check's explanation was requested (issue #51)
 * @param complexityClaim        the self-reported complexity (issue #17)
 * @param measuredComplexity     what measurement said (issue #17)
 * @param complexityClaimCorrect whether the claim matched measurement (issue #17)
 * @param solutionSeen           whether the reference solution was revealed on this
 *                               attempt before it ever passed (issue #82)
 */
public record AttemptView(
        String id,
        String exerciseId,
        String exerciseTitle,
        String domain,
        String form,
        String outcome,
        Instant startedAt,
        Instant endedAt,
        int submissionCount,
        int hintsTaken,
        boolean failingCaseRevealed,
        String revealHypothesis,
        boolean explanationRequested,
        String complexityClaim,
        String measuredComplexity,
        Boolean complexityClaimCorrect,
        boolean solutionSeen) {

    static AttemptView of(Attempt attempt, int submissionCount) {
        return new AttemptView(
                attempt.id().toString(),
                attempt.exerciseId(),
                attempt.exerciseTitle(),
                attempt.domain(),
                attempt.form(),
                attempt.outcome().name(),
                attempt.startedAt(),
                attempt.endedAt(),
                submissionCount,
                attempt.hintsTaken(),
                attempt.failingCaseRevealed(),
                attempt.revealHypothesis(),
                attempt.explanationRequested(),
                attempt.complexityClaim(),
                attempt.measuredComplexity(),
                attempt.complexityClaimCorrect(),
                attempt.solutionSeen());
    }

    static AttemptView of(AttemptWithCount withCount) {
        return of(withCount.attempt(), withCount.submissionCount());
    }
}
