package com.sweprep.backend.scheduler;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The tunable knobs behind the challenge priority score (issue #21, issue #8's decision
 * "Challenges: a priority score, not a due date"). Every default here is deliberately
 * configurable so the scoring balance can move without a code change, the same posture
 * {@link com.sweprep.backend.learned.LearnedProperties} takes for the learned criterion.
 *
 * @param minIntervalDays    the hard floor: a challenge attempted this many days ago or
 *                            more recently is never selected, whatever its score - "a
 *                            hard floor on minimum interval, because re-solving something
 *                            from two days ago is memorisation, not learning" (issue #21).
 *                            Defaults to 2, so a gap of exactly two days is still excluded
 *                            and three or more is required
 * @param maxNewPerWeek       the weekly cap on introducing a never-attempted challenge
 *                            (issue #21's "new-problem introductions are capped per
 *                            week"). Defaults to 3
 * @param cleanPassGapLadder  the expanding minimum gap, in days, a challenge must clear
 *                            since its last attempt before it competes on overdueness
 *                            again, indexed by how many distinct days it has earned a
 *                            {@link ChallengeQuality#PERFECT} pass on - the same
 *                            "ladder indexed by count, clamped to the last rung" shape
 *                            {@link com.sweprep.backend.learned.LearnedProperties#gapAfter}
 *                            uses for the learned criterion, but its own independent
 *                            config: the two answer different questions (whether an item
 *                            is "learned" for the readiness picture, versus how long a
 *                            challenge slot should leave a mastered problem alone) and
 *                            must stay tunable separately. The last rung is deliberately
 *                            months-scale, not weeks: "a problem passed cleanly three
 *                            times earns an interval of months - retirement, not
 *                            removal" (issue #21). Defaults to {@code [1, 3, 14, 90]}
 * @param overdueWeight       how strongly days-overdue-past-its-required-gap raises the
 *                            score. Defaults to 1.0
 * @param badnessWeight       how strongly a poor last quality score raises the score.
 *                            Defaults to 3.0
 * @param coverageWeight      how strongly an under-covered topic raises the score.
 *                            Defaults to 2.0
 * @param newProblemBaseline  the flat score a never-attempted challenge starts from,
 *                            before its topic-coverage bonus. Tuned low enough that a
 *                            genuinely weak due problem always outscores it - the
 *                            "review debt first, new problems second" ordering (issue
 *                            #21) - while still competing once nothing is meaningfully
 *                            overdue. Defaults to 1.0
 */
@ConfigurationProperties(prefix = "sweprep.scheduler")
public record ChallengeSchedulerProperties(
        Integer minIntervalDays,
        Integer maxNewPerWeek,
        List<Integer> cleanPassGapLadder,
        Double overdueWeight,
        Double badnessWeight,
        Double coverageWeight,
        Double newProblemBaseline) {

    private static final List<Integer> DEFAULT_LADDER = List.of(1, 3, 14, 90);

    public ChallengeSchedulerProperties {
        minIntervalDays = (minIntervalDays == null || minIntervalDays < 0) ? 2 : minIntervalDays;
        maxNewPerWeek = (maxNewPerWeek == null || maxNewPerWeek < 0) ? 3 : maxNewPerWeek;
        List<Integer> ladder = (cleanPassGapLadder == null || cleanPassGapLadder.isEmpty())
                ? DEFAULT_LADDER
                : cleanPassGapLadder;
        cleanPassGapLadder = ladder.stream().map(gap -> gap == null || gap < 0 ? 0 : gap).toList();
        overdueWeight = overdueWeight == null ? 1.0 : overdueWeight;
        badnessWeight = badnessWeight == null ? 3.0 : badnessWeight;
        coverageWeight = coverageWeight == null ? 2.0 : coverageWeight;
        newProblemBaseline = newProblemBaseline == null ? 1.0 : newProblemBaseline;
    }

    /**
     * The minimum gap, in days, a challenge must clear since its last attempt before it
     * is treated as overdue again, given how many distinct days it has earned a clean
     * ({@link ChallengeQuality#PERFECT}) pass so far - {@code cleanPassGapLadder[cleanPasses]},
     * clamped to the last rung once {@code cleanPasses} runs past the ladder's length.
     */
    public int gapAfter(int cleanPasses) {
        int index = Math.max(0, Math.min(cleanPasses, cleanPassGapLadder.size() - 1));
        return cleanPassGapLadder.get(index);
    }
}
