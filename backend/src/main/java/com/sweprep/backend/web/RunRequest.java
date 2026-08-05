package com.sweprep.backend.web;

/**
 * A run request from the editor: the submission source to compile and run.
 *
 * @param code the full source the user wrote (a complete {@code Solution} class)
 */
public record RunRequest(String code) {}
