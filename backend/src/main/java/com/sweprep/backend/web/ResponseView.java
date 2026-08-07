package com.sweprep.backend.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * How the editor should let the solver answer one exercise. The {@code kind} tells
 * the front end which control to render: a code editor seeded with {@code stub} in
 * {@code language}, a list of {@code options} to pick from, or a plain {@code freeText}
 * box (the "predict the output" rep, issue #18, whose answer is a value typed in and
 * matched exactly after normalisation). Fields that do not apply to a kind are omitted
 * from the JSON.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResponseView(String kind, String language, String stub, List<String> options) {

    static ResponseView code(String language, String stub) {
        return new ResponseView("code", language, stub, null);
    }

    static ResponseView choice(List<String> options) {
        return new ResponseView("choice", null, null, List.copyOf(options));
    }

    static ResponseView freeText() {
        return new ResponseView("freeText", null, null, null);
    }
}
