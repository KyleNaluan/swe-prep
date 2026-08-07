package com.sweprep.backend.session;

import java.time.Instant;

/**
 * What the app needs to know about today's session on open (issue #19): whether the
 * day is already complete, when it was completed (null until it is), and the current
 * streak of consecutive completed days. It is read on every app open, so it is a single
 * indexed lookup, never a scan - the "seconds to the first rep" criterion depends on
 * this staying cheap.
 *
 * <p>The streak is a descriptive record, never a currency (issue #7): completing the
 * warm-up earns it, and it is shown so a good day feels like one - it is not spent,
 * traded, or lost to a missed main exercise.
 *
 * @param dayComplete whether today's warm-up is done
 * @param completedAt when it was done, or null if it is not yet
 * @param streak      consecutive completed days ending today (or yesterday if today is
 *                    not yet complete, so an ongoing streak shows before the day's rep)
 */
public record SessionStatus(boolean dayComplete, Instant completedAt, int streak) {}
