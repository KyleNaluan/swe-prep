package com.sweprep.backend.readiness;

/**
 * A topic that has been practised before but not touched in a while (issue #22): "Not
 * touched in 3 weeks: dynamic programming" from decision issue #7's own mockup. Only
 * topics with at least one attempted exercise are ever candidates - an untouched topic
 * is "not covered" ({@link ReadinessSummary#checksToCriterion}), not stale.
 *
 * @param topic             the topic name, as authored in content
 * @param daysSinceTouched  days since the most recently attempted exercise tagged with
 *                          this topic was last opened, whatever its outcome
 */
public record StaleTopic(String topic, long daysSinceTouched) {}
