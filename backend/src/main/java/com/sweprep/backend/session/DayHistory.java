package com.sweprep.backend.session;

import java.time.LocalDate;

/**
 * One day's place in the honest record {@link StreakCalculator#history} projects for
 * the day ribbon (Today) and the year-record grid (Readiness) - a picture of {@code
 * day_completion}, never a currency (issue #7).
 *
 * @param date          the calendar day
 * @param completed     whether the warm-up was finished that day
 * @param doubleSession whether a {@code CHALLENGE} was also solved that day - the
 *                      "warm-up plus a challenge" bar
 * @param bridged       whether this day was missed but bridged by the next day's
 *                      double session (issue #22's repair mechanic) - always {@code
 *                      false} when {@code completed} is {@code true}
 */
public record DayHistory(LocalDate date, boolean completed, boolean doubleSession, boolean bridged) {}
