package com.sweprep.backend.web;

import com.sweprep.backend.exercise.Complexity;

/**
 * The solver's complexity self-report (issue #17), stated before the authored target
 * is revealed.
 *
 * @param time  the claimed time complexity
 * @param space the claimed space complexity (self-reported only - never empirically
 *              checked, see {@link com.sweprep.backend.exercise.ComplexityCheck})
 */
public record ComplexityClaimRequest(Complexity time, Complexity space) {}
