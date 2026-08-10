package com.sweprep.backend.sql;

import java.time.Duration;

/**
 * Everything a {@link SqlRunner} needs to run one submitted query, and nothing about
 * expected rows or a verdict - the runner only executes.
 *
 * @param fixture the fixture schema to run against, already validated at content-load time
 *                to be a safe identifier ({@code ExerciseParser})
 * @param query   the solver's submitted SQL text, run verbatim as a read-only role in a
 *                transaction that is always rolled back (issue #25)
 * @param timeout wall-clock limit on execution, matching the language runner's timeout
 */
public record SqlExecutionRequest(String fixture, String query, Duration timeout) {}
