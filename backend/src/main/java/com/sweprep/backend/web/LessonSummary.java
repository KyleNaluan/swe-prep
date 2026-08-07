package com.sweprep.backend.web;

import com.sweprep.backend.exercise.Lesson;

/**
 * The little a lesson browser needs to list one lesson and let the reader pick it, without
 * loading its body or prompts (issue #46/#41).
 *
 * @param promptCount how many self-explanation prompts the lesson carries, so the browser
 *                    can hint that reading it is generative
 */
public record LessonSummary(
        String id, String title, String domain, String difficulty, int promptCount) {

    static LessonSummary of(Lesson lesson) {
        return new LessonSummary(
                lesson.id(),
                lesson.title(),
                lesson.domain(),
                lesson.difficulty().name(),
                lesson.prompts().size());
    }
}
