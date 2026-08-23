package com.sweprep.backend.language;

import com.sweprep.backend.exercise.Signature;

/**
 * Bridges the language-neutral exercise model to one concrete language.
 *
 * <p>Both the editable stub the user starts from and the harness that calls the
 * submission are <em>generated</em> from a {@link Signature}, never hand-written
 * per problem. This is the seam that makes the language-neutral test-case design
 * real: a case authored once as JSON runs in every language that has an adapter.
 *
 * @see com.sweprep.backend.exercise.DataType
 */
public interface LanguageAdapter {

    /** Identifier of the language this adapter targets, e.g. {@code "java"}. */
    String languageId();

    /**
     * The filename the submission's own source is written to alongside the generated
     * harness, e.g. {@code "Solution.java"} or {@code "Solution.py"}. A caller that
     * assembles an {@link com.sweprep.backend.runner.ExecutionRequest} writes the
     * submission under this name rather than a language hardcoded one, which is what
     * lets the same grading code run any adapter's submission.
     */
    String submissionFileName();

    /**
     * The editable stub shown in the editor: a compiling skeleton of the method
     * to implement, derived from the signature.
     */
    String generateStub(Signature signature);

    /**
     * The harness that deserialises each case's JSON arguments into this
     * language's types, calls the submission, and compares the result against the
     * expected JSON. The submission's own source is compiled alongside it.
     */
    GeneratedHarness generateHarness(Signature signature);

    /**
     * The harness that runs the submission repeatedly against one input at a growing
     * measured size, recording each repetition's wall-clock time rather than comparing a
     * return value (issue #17) - this is the runner's "second execution mode" the
     * complexity self-report flow measures scaling with. Generated from the signature
     * alone, exactly like {@link #generateHarness}, so no per-language timing code is
     * ever hand-written. There is no {@link com.sweprep.backend.exercise.Comparison}
     * here: nothing is graded, only timed.
     *
     * <p>Every language's timing harness takes the same five program arguments, in this
     * order, so {@code ScalingMeasurer} drives them all identically: the input file, the
     * warm-up time budget in nanoseconds, the maximum number of warm-up calls, the number
     * of timed repetitions, and the result file to write. Warm-up runs untimed calls
     * until either bound is hit (always at least one call); each timed repetition then
     * records its own {@code elapsedNanos}, or {@code threw}. Binding the arguments and
     * constructing the solution happen outside every timed window.
     */
    GeneratedHarness generateTimingHarness(Signature signature);
}
