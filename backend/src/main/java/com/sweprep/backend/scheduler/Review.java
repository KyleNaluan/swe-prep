package com.sweprep.backend.scheduler;

import java.time.LocalDate;

/**
 * One completed rep review, reduced to the two facts a spaced-repetition algorithm needs:
 * the calendar day it happened on and its {@link ReviewQuality quality} (issue #20). Nothing
 * else - the ticket is explicit that time taken is not an input to scheduling, so there is no
 * duration field here to be tempted into using as one.
 *
 * @param reviewedOn the calendar day the review's attempt ended, in the app's clock zone
 * @param quality    the 0-5 SM-2 quality score ({@link ReviewQuality})
 */
public record Review(LocalDate reviewedOn, int quality) {}
