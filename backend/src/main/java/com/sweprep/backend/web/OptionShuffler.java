package com.sweprep.backend.web;

import java.util.List;

/**
 * Decides the order a check's multiple-choice options are presented in (issue #59).
 *
 * <p>Options are authored in file order, and an author drafting a batch in one sitting
 * naturally writes the correct answer first - so serving them in file order lets a
 * learner exploit answer <em>position</em> instead of knowing the material (in the first
 * authored AI/ML batch every one of 12 checks had the key first). Grading matches the
 * answer key by <em>text</em>, never by index ({@code AnswerKeyGrader}, and
 * {@code ChoiceKeys.matchesKey} at load time), so re-ordering the presentation cannot
 * affect correctness - which is exactly what makes shuffling safe.
 *
 * <p>The order must be <b>stable within a sitting</b>: an order that reshuffles under the
 * learner on a refresh or a resumed session is its own usability bug. But it must also
 * <b>rotate between sittings</b> - under spaced repetition a learner meets the same check
 * many times, so a permanently fixed order just moves the position tell onto a slower
 * clock. The production bean reconciles the two by keying on the calendar day (see below).
 * This is a seam so the shuffle is deterministic under test (the shipped path is the tested
 * path), rather than switched off in test config.
 *
 * @see DeterministicOptionShuffler the production bean, keyed on (exercise id, user, day)
 */
public interface OptionShuffler {

    /**
     * Returns {@code options} re-ordered for presentation. The {@code key} identifies the
     * check; an implementation that keys its ordering on it gives the same order every
     * time the same check is fetched, so the learner never sees it reshuffle. The result
     * is always a permutation of the input - the same options, nothing added or dropped.
     */
    List<String> order(String key, List<String> options);

    /** A no-op ordering (file order), for contexts where shuffling is irrelevant. */
    OptionShuffler IDENTITY = (key, options) -> List.copyOf(options);
}
