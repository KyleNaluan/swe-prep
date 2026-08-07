package com.sweprep.backend.attempt;

/**
 * The outcome of committing a self-check explanation and revealing the model answer
 * (issue #41): the persisted {@link Submission} that froze the produced text, and the
 * {@code modelAnswer} now disclosed for the learner to grade themselves against.
 *
 * <p>The reveal is the point of no return in the record. The submission is inserted here,
 * <em>before</em> the model answer is handed back, so the produced text is captured exactly
 * as the learner wrote it cold - a later self-rating is stamped onto this same row. A rating
 * made after peeking is therefore distinguishable: the record holds what was produced before
 * the answer was seen, not a copy edited afterwards.
 *
 * @param submission  the committed self-check submission ({@link SubmissionOutcome#SELF_RATED})
 * @param modelAnswer the model answer revealed for self-comparison
 */
public record SelfCheckReveal(Submission submission, String modelAnswer) {}
