package com.sweprep.backend.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * How the editor should let the solver answer one exercise. The {@code kind} tells
 * the front end which control to render: a code editor seeded with {@code stub} in
 * {@code language}, a list of {@code options} to pick from, a plain {@code freeText}
 * box (the machine-graded "predict the output" rep, issue #18, whose answer is a value
 * typed in and matched exactly after normalisation), or a {@code selfCheck} box (the
 * self-graded "explain in your own words" item, issue #41, produce-then-reveal). Fields
 * that do not apply to a kind are omitted from the JSON.
 *
 * <p>{@code freeText} and {@code selfCheck} share the same underlying {@link
 * com.sweprep.backend.exercise.Response.FreeText} response and differ only in grading, but
 * they render very differently - one is submitted for a machine verdict, the other reveals a
 * model answer for self-comparison - so the {@code kind} carries the distinction the editor
 * needs. The model answer is never shipped here: it is disclosed only after the learner
 * commits their own text, through the reveal endpoint (issue #41).
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

    static ResponseView selfCheck() {
        return new ResponseView("selfCheck", null, null, null);
    }

    /**
     * A SQL query editor (issue #25): {@code language} is always {@code "sql"} and {@code
     * stub} seeds a blank query rather than a generated one - there is no {@code Signature}
     * to generate it from, since {@link com.sweprep.backend.exercise.Response.Query} is a
     * marker like {@code freeText}. It shares this record's {@code language}/{@code stub}
     * fields with {@code code} on purpose: the editor renders both the same way.
     */
    static ResponseView query(String stub) {
        return new ResponseView("query", "sql", stub, null);
    }
}
