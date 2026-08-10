package com.sweprep.backend.session;

import java.time.Instant;

/**
 * What the app needs to know about today's session on open (issue #19): whether the
 * day is already complete, when it was completed (null until it is), and the current
 * streak of consecutive completed days. It is read on every app open, so it is a single
 * indexed lookup plus one cheap query, never a scan - the "seconds to the first rep"
 * criterion depends on this staying cheap.
 *
 * <p>The streak is a descriptive record, never a currency (issue #7): completing the
 * warm-up earns it, and it is shown so a good day feels like one - it is not spent,
 * traded, or lost to a missed main exercise.
 *
 * <p>{@link #repairsRemainingThisMonth} and {@link #repairPending} surface the capped
 * repair mechanic (issue #22, decision #7 item 5) honestly: a plain count and a plain
 * boolean, never a currency either - see {@link StreakCalculator}.
 *
 * @param dayComplete               whether today's warm-up is done
 * @param completedAt               when it was done, or null if it is not yet
 * @param streak                    consecutive completed days ending today (or
 *                                  yesterday if today is not yet complete, so an
 *                                  ongoing streak shows before the day's rep), a
 *                                  repaired gap already bridged
 * @param repairsRemainingThisMonth how many more missed days can still be repaired by
 *                                  a double session this calendar month
 * @param repairPending             whether solving a challenge today, on top of the
 *                                  required warm-up, would repair a currently broken
 *                                  streak right now
 */
public record SessionStatus(
        boolean dayComplete,
        Instant completedAt,
        int streak,
        int repairsRemainingThisMonth,
        boolean repairPending) {}
