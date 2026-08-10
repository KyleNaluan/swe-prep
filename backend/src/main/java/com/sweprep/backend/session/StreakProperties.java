package com.sweprep.backend.session;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Caps the streak repair mechanic (issue #22, decision issue #7 item 5): "a missed day
 * may be repaired by a double session the next day, capped at roughly two per month so
 * it stays meaningful." See {@link StreakCalculator} for how the cap is enforced.
 *
 * @param maxRepairsPerMonth how many missed days can be bridged by a double session
 *                           within one calendar month; defaults to 2, floored at 0
 *                           (0 turns the mechanic off - every miss breaks the streak)
 */
@ConfigurationProperties(prefix = "sweprep.streak")
public record StreakProperties(Integer maxRepairsPerMonth) {

    public StreakProperties {
        maxRepairsPerMonth = (maxRepairsPerMonth == null || maxRepairsPerMonth < 0) ? 2 : maxRepairsPerMonth;
    }
}
