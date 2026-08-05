package com.sweprep.backend.grader;

import com.sweprep.backend.exercise.Exercise;

/**
 * Decides whether a submission passes. A grader owns the pass/fail decision; it
 * delegates any execution to a runner rather than executing itself. Keeping the
 * decision separate from execution is what lets a later concept exercise be
 * graded with no runner at all (see the exercise abstraction, issue #6).
 */
public interface Grader {

    Verdict grade(Exercise exercise, String submission);
}
