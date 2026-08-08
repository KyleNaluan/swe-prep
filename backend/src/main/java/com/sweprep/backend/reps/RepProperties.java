package com.sweprep.backend.reps;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How the warm-up set is shaped (issue #18). The defaults encode the daily-core
 * decision (issue #3): eight reps, and no more than two in a row sharing a topic or
 * domain so the set is always interleaved rather than blocked (design revision t3,
 * section 4.2). {@code warmupSize} is a hard cap on the four-minute core - it is
 * deliberately small and must not grow.
 *
 * <p>Which families are active is <em>not</em> a config property: it is the user's own role choice
 * (issue #40), stored per user and read by {@link WarmupService} from {@link
 * com.sweprep.backend.role.RoleService}. Keeping it out of here is deliberate - there is one family
 * filter, sourced from the user's durable choice, not a config default competing with it.
 *
 * @param warmupSize         reps in one warm-up set
 * @param maxConsecutiveSame most reps in a row that may share a topic or domain
 */
@ConfigurationProperties(prefix = "sweprep.reps")
public record RepProperties(Integer warmupSize, Integer maxConsecutiveSame) {

    public RepProperties {
        warmupSize = warmupSize == null ? 8 : warmupSize;
        maxConsecutiveSame = maxConsecutiveSame == null ? 2 : maxConsecutiveSame;
    }
}
