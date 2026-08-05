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
}
