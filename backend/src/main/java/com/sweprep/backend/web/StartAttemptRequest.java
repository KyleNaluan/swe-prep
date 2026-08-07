package com.sweprep.backend.web;

/**
 * A request to open a new attempt: which exercise the sitting is with.
 *
 * @param exerciseId the content exercise id to practise
 */
public record StartAttemptRequest(String exerciseId) {}
