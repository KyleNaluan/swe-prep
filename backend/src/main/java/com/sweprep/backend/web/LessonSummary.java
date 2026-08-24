package com.sweprep.backend.web;

import com.sweprep.backend.exercise.Lesson;
import java.util.List;

/**
 * The little a lesson browser needs to list one lesson and let the reader pick it, without
 * loading its body or prompts (issue #46/#41).
 *
 * @param promptCount how many self-explanation prompts the lesson carries, so the browser
 *                    can hint that reading it is generative
 * @param topics      the lesson's topic tags (issue #90's {@code TreeBrowser} concept
 *                    tier groups by these), so the client can build the tree without a
 *                    second fetch per item
 * @param family      the lesson's role-family tags (issue #90's Learn difficulty-and-family
 *                    filter row, in its own labeled group distinct from difficulty per the
 *                    captain's refinement), empty when untagged
 */
public record LessonSummary(
        String id,
        String title,
        String domain,
        String difficulty,
        int promptCount,
        List<String> topics,
        List<String> family) {

    static LessonSummary of(Lesson lesson) {
        return new LessonSummary(
                lesson.id(),
                lesson.title(),
                lesson.domain(),
                lesson.difficulty().name(),
                lesson.prompts().size(),
                lesson.topics(),
                lesson.family().stream().map(Enum::name).toList());
    }
}
