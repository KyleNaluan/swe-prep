package com.sweprep.backend.exercise;

import java.util.List;

/**
 * A language-neutral declaration of the method a submission must implement.
 *
 * <p>Both the editable stub the user starts from and the harness that calls the
 * submission are generated from this declaration by a language adapter; neither
 * is hand-written per problem or per language.
 *
 * @param methodName the name of the method to implement and to call
 * @param parameters its parameters, in call order
 * @param returnType the type of the value it returns
 */
public record Signature(String methodName, List<Parameter> parameters, DataType returnType) {

    public Signature {
        parameters = List.copyOf(parameters);
    }

    /** A single parameter of a {@link Signature}. */
    public record Parameter(String name, DataType type) {}
}
