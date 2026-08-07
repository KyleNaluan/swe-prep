package com.sweprep.backend.exercise;

/**
 * How hard an exercise is, used later by the scheduler to weigh what is worth a
 * session (see the scheduling decision, issue #8). It is an attribute of the
 * exercise here; nothing in this ticket reads it yet.
 */
public enum Difficulty {
    EASY,
    MEDIUM,
    HARD
}
