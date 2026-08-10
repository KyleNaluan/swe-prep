package com.sweprep.backend.scheduler;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * The challenge priority score itself (issue #21, issue #8's decision "Challenges: a
 * priority score, not a due date"): pure, no database, like {@link Sm2Scheduler} and
 * {@link com.sweprep.backend.learned.LearnedCriterion}. What is worth today's one
 * challenge slot is not what is owed by a due date - it is the highest-scoring
 * candidate, full stop: <em>"Score every problem on... Take the highest scorer"</em>
 * (issue #8's resolution).
 *
 * <p>Four ingredients feed the score, matching the resolution's list exactly:
 *
 * <ul>
 *   <li><b>Time since last attempt</b> and <b>how badly it went</b> are combined into one
 *       overdueness term rather than two independent ones, because the two interact: a
 *       challenge that went badly needs a <em>shorter</em> gap before it is worth
 *       revisiting than one that went perfectly, and a challenge passed cleanly again and
 *       again needs a <em>longer</em> one. {@link ChallengeSchedulerProperties#gapAfter}
 *       supplies that required gap from the count of clean passes so far; {@code
 *       overdue = daysSinceLastAttempt - requiredGap} is the term itself, and it can go
 *       negative for a challenge that is not yet due again - which is exactly what makes
 *       a repeatedly-passed challenge stop scoring competitively without ever being
 *       excluded outright (issue #21's "retirement, not removal").
 *   <li><b>How badly the last attempt went</b> also has its own direct term - {@code 5 -
 *       lastQuality} - so a challenge that was just failed outscores one that was merely
 *       old, even before either is overdue by the gap above.
 *   <li><b>Whether the pattern is under-covered</b> is {@link ChallengeCandidate#topicCoverage}, a
 *       fraction the caller already reduced from the catalog; the score adds a bonus
 *       proportional to how far from full coverage it is.
 * </ul>
 *
 * <p>Two more rules sit outside that formula, deliberately, because they are gates, not
 * inputs the score should merely lean on:
 *
 * <ul>
 *   <li>The <b>hard floor on minimum interval</b>: a challenge attempted within {@link
 *       ChallengeSchedulerProperties#minIntervalDays} is excluded from the candidate pool
 *       entirely - {@link #scoreOf} returns {@link OptionalDouble#empty()} for it - not
 *       merely scored low. "Never selected" is the acceptance criterion's own wording, and
 *       a soft penalty could still be outscored into selection by a large enough badness
 *       or coverage bonus; a gate cannot.
 *   <li>The <b>weekly cap on new introductions</b>: a never-attempted challenge ({@link
 *       ChallengeCandidate#reviews} empty) is excluded the same hard way once {@code
 *       newIntroductionsThisWeek} has already reached {@link
 *       ChallengeSchedulerProperties#maxNewPerWeek}. Below the cap it scores from {@link
 *       ChallengeSchedulerProperties#newProblemBaseline} plus its own coverage bonus - a
 *       flat baseline deliberately tuned low, so "review debt first, new problems second"
 *       falls out of the ordinary scoring comparison rather than a separate priority lane:
 *       a genuinely weak due challenge's badness and overdue terms push its score well past
 *       the baseline, while a challenge with nothing meaningfully wrong with it does not.
 * </ul>
 */
public final class ChallengePriority {

    private ChallengePriority() {}

    /**
     * The single highest-scoring eligible candidate, or empty when every candidate is
     * gated out (an empty candidate list, or every challenge either within its minimum
     * interval or - being new - over the weekly cap). Ties keep the first-encountered
     * candidate, so a stable {@code candidates} order (e.g. catalog order) makes the pick
     * deterministic.
     */
    public static Optional<String> select(
            List<ChallengeCandidate> candidates,
            LocalDate today,
            ChallengeSchedulerProperties props,
            int newIntroductionsThisWeek) {
        String bestId = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (ChallengeCandidate candidate : candidates) {
            OptionalDouble score = scoreOf(candidate, today, props, newIntroductionsThisWeek);
            if (score.isPresent() && score.getAsDouble() > bestScore) {
                bestScore = score.getAsDouble();
                bestId = candidate.exerciseId();
            }
        }
        return Optional.ofNullable(bestId);
    }

    /**
     * One candidate's priority score, or {@link OptionalDouble#empty()} when it is gated
     * out by the minimum-interval floor or the weekly new-introduction cap. Exposed
     * separately from {@link #select} so each gate and each scoring term is directly
     * testable in isolation.
     */
    public static OptionalDouble scoreOf(
            ChallengeCandidate candidate,
            LocalDate today,
            ChallengeSchedulerProperties props,
            int newIntroductionsThisWeek) {
        double coverageBonus = (1.0 - candidate.topicCoverage()) * props.coverageWeight();
        List<Review> reviews = candidate.reviews();

        if (reviews.isEmpty()) {
            if (newIntroductionsThisWeek >= props.maxNewPerWeek()) {
                return OptionalDouble.empty();
            }
            return OptionalDouble.of(props.newProblemBaseline() + coverageBonus);
        }

        Review last = reviews.get(reviews.size() - 1);
        long daysSinceLastAttempt = ChronoUnit.DAYS.between(last.reviewedOn(), today);
        if (daysSinceLastAttempt <= props.minIntervalDays()) {
            return OptionalDouble.empty();
        }

        long cleanPasses = reviews.stream()
                .filter(review -> review.quality() == ChallengeQuality.PERFECT)
                .map(Review::reviewedOn)
                .distinct()
                .count();
        int requiredGap = props.gapAfter((int) cleanPasses);
        double overdue = daysSinceLastAttempt - requiredGap;
        double badness = ChallengeQuality.PERFECT - last.quality();

        double score = overdue * props.overdueWeight() + badness * props.badnessWeight() + coverageBonus;
        return OptionalDouble.of(score);
    }
}
