package com.sweprep.backend.attempt;

import java.time.Instant;
import java.util.UUID;

/**
 * One sitting with an exercise, the durable record the schedulers (issue #8) read.
 *
 * <p>An attempt is created when practice starts and reaches a terminal
 * {@link AttemptOutcome} when it is solved or abandoned. It deliberately
 * over-captures (issue #15): alongside the outcome it holds every field the judging
 * decision (issue #5) says is recorded, including ones whose producing feature is
 * not built yet - {@link #hintsTaken} (the ladder is issue #16) and the complexity
 * claim-vs-measurement fields (measurement is issue #17). Those stay at their empty
 * defaults until their tickets populate them, so no later migration is needed.
 *
 * <p>{@link #exerciseId} points at content that lives only in the private content
 * repo (issue #4/#14), so {@link #exerciseTitle}, {@link #domain} and {@link #form}
 * are snapshotted here at creation rather than joined, keeping history readable even
 * if the content set changes or no clone is present.
 *
 * @param id                     stable identifier for this sitting
 * @param userId                 the person practising (issue #14's single user)
 * @param exerciseId             the content exercise id (not a foreign key)
 * @param exerciseTitle          the exercise title, snapshotted at creation
 * @param domain                 the exercise domain, snapshotted at creation
 * @param form                   {@code REP} or {@code CHALLENGE}, snapshotted
 * @param outcome                how the sitting ended, or {@code IN_PROGRESS}
 * @param startedAt              when the sitting began
 * @param endedAt                when it reached a terminal outcome, or {@code null}
 * @param hintsTaken             hint-ladder rungs climbed (issue #16 populates)
 * @param failingCaseRevealed    whether the failing case was revealed (issue #5)
 * @param complexityClaim        the solver's self-reported complexity (issue #17)
 * @param measuredComplexity     what measurement said (issue #17)
 * @param complexityClaimCorrect whether the claim matched measurement (issue #17)
 */
public record Attempt(
        UUID id,
        UUID userId,
        String exerciseId,
        String exerciseTitle,
        String domain,
        String form,
        AttemptOutcome outcome,
        Instant startedAt,
        Instant endedAt,
        int hintsTaken,
        boolean failingCaseRevealed,
        String complexityClaim,
        String measuredComplexity,
        Boolean complexityClaimCorrect) {}
