package com.sweprep.backend.web;

/**
 * A run request from the editor: the answer to grade. For a coding exercise this
 * is the full source the solver wrote; for a choice exercise it is the option they
 * picked. Which one it is follows from the exercise's response spec, so the wire
 * carries a single neutral field.
 *
 * @param submission the solver's answer
 */
public record RunRequest(String submission) {}
