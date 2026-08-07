package com.sweprep.backend.attempt;

/**
 * How a learner rated their own free-text explanation against the revealed model answer
 * (issue #41, design revision t3 section 1.1). This is the self-graded produce-then-reveal
 * signal: the learner produces an explanation, the model answer is revealed, and they judge
 * themselves against it.
 *
 * <p>It is emphatically <strong>not</strong> a machine verdict and never a 0-5 quality
 * score. It is recorded against the submission (in {@code detail}) as a separate,
 * clearly-labelled generation/confidence signal and is structurally kept out of the
 * objective competence number: the submission carries {@link SubmissionOutcome#SELF_RATED},
 * never {@code PASSED}, so the successive-relearning criterion (issue #38) - which reads
 * only clean machine passes - cannot see it however the learner rates themselves.
 */
public enum SelfRating {
    /** The learner judged their explanation as good as the model answer. */
    NAILED_IT,
    /** The learner got the gist but missed or muddled parts of it. */
    PARTIAL,
    /** The learner could not produce a sound explanation. */
    MISSED
}
