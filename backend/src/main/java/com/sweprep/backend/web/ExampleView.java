package com.sweprep.backend.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sweprep.backend.exercise.Example;

/**
 * One worked example on the wire, straight off {@link Example} - already
 * display-formatted text, so this is a plain pass-through DTO with no shuffling or
 * withholding logic (unlike {@link ResponseView}'s choice options). {@code
 * explanation} is omitted from the JSON entirely when the exercise carries none,
 * rather than shipping a null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExampleView(String input, String output, String explanation) {

    static ExampleView of(Example example) {
        return new ExampleView(example.input(), example.output(), example.explanation());
    }
}
