package com.sweprep.backend.web;

/**
 * The body of a self-check reveal (issue #41): the free-text explanation the learner
 * produced, committed the moment the model answer is revealed.
 *
 * @param produced the learner's own explanation, produced before seeing the model answer
 */
public record SelfCheckRevealRequest(String produced) {}
