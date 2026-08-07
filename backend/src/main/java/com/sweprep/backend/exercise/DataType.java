package com.sweprep.backend.exercise;

/**
 * The language-neutral type vocabulary a {@link Signature} is written in.
 *
 * <p>These names carry no language syntax. Each {@code LanguageAdapter} is
 * responsible for mapping a {@code DataType} onto its own concrete type when it
 * generates a stub and a harness, which is what lets a single signature (and the
 * test cases written against it) run in every language ever added.
 *
 * <p>Only the members the first hardcoded problem needs are defined; new members
 * are added as new problems require them, never a Java-specific type here.
 */
public enum DataType {
    INT,
    INT_ARRAY,
    INT_MATRIX,
    BOOLEAN,
    STRING
}
