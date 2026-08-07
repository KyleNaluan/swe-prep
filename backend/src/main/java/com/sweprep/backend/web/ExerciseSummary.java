package com.sweprep.backend.web;

import com.sweprep.backend.exercise.Exercise;

/**
 * The little an exercise picker needs to list one exercise and let the solver
 * choose it, without loading its full prompt or response spec.
 */
public record ExerciseSummary(
        String id, String title, String domain, String difficulty, String form) {

    static ExerciseSummary of(Exercise exercise) {
        return new ExerciseSummary(
                exercise.id(),
                exercise.title(),
                exercise.domain(),
                exercise.difficulty().name(),
                exercise.form().name());
    }
}
