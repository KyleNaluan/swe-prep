package com.sweprep.backend.web;

import com.sweprep.backend.exercise.Exercise;
import java.util.List;

/**
 * The little an exercise picker needs to list one exercise and let the solver
 * choose it, without loading its full prompt or response spec.
 *
 * @param topics the exercise's topic tags (issue #90's {@code TreeBrowser} pattern
 *               tier groups by these), so the client can build the tree without a
 *               second fetch per item
 * @param family the exercise's role-family tags (issue #90's Practice/Learn
 *               difficulty-and-family filter row, in its own labeled group
 *               distinct from difficulty per the captain's refinement), empty
 *               when untagged
 */
public record ExerciseSummary(
        String id,
        String title,
        String domain,
        String difficulty,
        String form,
        List<String> topics,
        List<String> family) {

    static ExerciseSummary of(Exercise exercise) {
        return new ExerciseSummary(
                exercise.id(),
                exercise.title(),
                exercise.domain(),
                exercise.difficulty().name(),
                exercise.form().name(),
                exercise.topics(),
                exercise.family().stream().map(Enum::name).toList());
    }
}
