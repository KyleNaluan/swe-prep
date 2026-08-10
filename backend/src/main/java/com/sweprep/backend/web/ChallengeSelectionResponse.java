package com.sweprep.backend.web;

/**
 * What {@code GET /api/challenges/next} returns: the priority-scored main exercise for
 * today (issue #21), or a {@code null} {@code exercise} when the catalog holds no
 * {@code CHALLENGE} or every one is currently gated out. The editor falls back to its own
 * static pick in that case rather than treating it as an error - see {@code Practice.tsx}.
 */
public record ChallengeSelectionResponse(ExerciseSummary exercise) {}
