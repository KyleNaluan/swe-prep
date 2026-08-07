package com.sweprep.backend.reps;

import com.sweprep.backend.exercise.Family;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How the warm-up set is shaped (issue #18). The defaults encode the daily-core
 * decision (issue #3): eight reps, and no more than two in a row sharing a topic or
 * domain so the set is always interleaved rather than blocked (design revision t3,
 * section 4.2). {@code warmupSize} is a hard cap on the four-minute core - it is
 * deliberately small and must not grow.
 *
 * <p>{@code activeFamilies} is the temporary home of the family filter until its user
 * setting and UI land (#40). Empty (the default) means no family restriction - every
 * family is treated as active - so nothing is suppressed before the user can choose;
 * the suppression mechanism itself lives in {@link WarmupSelector} and is exercised
 * whenever this list is non-empty. {@link Family#CORE} and {@link Family#PROFESSIONAL}
 * are always active regardless of this list.
 *
 * @param warmupSize         reps in one warm-up set
 * @param maxConsecutiveSame most reps in a row that may share a topic or domain
 * @param activeFamilies     families the user has turned on; empty means all active
 */
@ConfigurationProperties(prefix = "sweprep.reps")
public record RepProperties(
        Integer warmupSize, Integer maxConsecutiveSame, List<Family> activeFamilies) {

    public RepProperties {
        warmupSize = warmupSize == null ? 8 : warmupSize;
        maxConsecutiveSame = maxConsecutiveSame == null ? 2 : maxConsecutiveSame;
        activeFamilies = activeFamilies == null ? List.of() : List.copyOf(activeFamilies);
    }
}
