package com.sweprep.backend.readiness;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunes the shaky/staleness axes {@link TopicReadinessCalculator} derives (issue #22).
 * Both thresholds apply only to a topic that has been attempted at least once - an
 * untouched topic is "not covered" ({@link ReadinessSummary#checksToCriterion}), a
 * different axis entirely, never counted as shaky or stale.
 *
 * @param shakyThreshold the minimum achieved/total ratio of learned reps a topic needs
 *                       to avoid being flagged shaky; defaults to 0.5 (fewer than half
 *                       of its attempted patterns reaching the learned criterion)
 * @param staleAfterDays how many days since a topic was last touched before it is
 *                       flagged stale; defaults to 14
 */
@ConfigurationProperties(prefix = "sweprep.readiness")
public record ReadinessProperties(Double shakyThreshold, Integer staleAfterDays) {

    public ReadinessProperties {
        shakyThreshold = (shakyThreshold == null || shakyThreshold < 0) ? 0.5 : shakyThreshold;
        staleAfterDays = (staleAfterDays == null || staleAfterDays < 1) ? 14 : staleAfterDays;
    }
}
