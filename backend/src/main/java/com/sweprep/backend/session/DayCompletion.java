package com.sweprep.backend.session;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One day a user finished the warm-up, so the day is complete (issue #19). The record
 * is deliberately minimal - a completed day is a fact, not a place to hang the messy
 * detail of what was practised (that lives in {@code attempt}, issue #15). Once written
 * nothing after the warm-up can change it: declining the optional main exercise or
 * abandoning one part-way never touches this row.
 *
 * @param userId      the person whose day it is (issue #14's per-row ownership)
 * @param date        the calendar day, in the app's local zone
 * @param completedAt the instant the warm-up was finished
 */
public record DayCompletion(UUID userId, LocalDate date, Instant completedAt) {}
