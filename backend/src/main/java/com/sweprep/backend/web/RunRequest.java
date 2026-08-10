package com.sweprep.backend.web;

/**
 * A run request from the editor: the answer to grade. For a coding exercise this
 * is the full source the solver wrote; for a choice exercise it is the option they
 * picked. Which one it is follows from the exercise's response spec, so the wire
 * carries a single neutral field.
 *
 * @param submission the solver's answer
 * @param language   the language {@code submission} is written in (issue #26), e.g.
 *                   {@code "java"} or {@code "python"} - meaningful, and required to
 *                   be one {@link com.sweprep.backend.language.LanguageAdapterRegistry}
 *                   actually serves, only for a code response; {@code null}/blank
 *                   defaults to Java for every response kind, since a choice, free-text
 *                   or SQL answer has no language to name
 */
public record RunRequest(String submission, String language) {}
