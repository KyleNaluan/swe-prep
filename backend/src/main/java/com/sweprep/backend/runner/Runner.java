package com.sweprep.backend.runner;

/**
 * Executes a program and reports what happened. A runner only executes; it never
 * decides whether a submission passed - that is a grader's job. Keeping the two
 * apart is what lets a later concept exercise be graded with no runner at all
 * (see the exercise abstraction decision, issue #6).
 *
 * <p>This is the swappable execution seam. Today the only implementation runs a
 * local subprocess with no sandbox, which is safe because there is exactly one
 * user and it is his own code. If the app is ever hosted, a container-backed
 * runner slots in here without touching the exercise model or the grader (see
 * the platform decision, issue #2).
 */
public interface Runner {

    /**
     * The language this runner compiles and runs, e.g. {@code "java"}. Matches the
     * {@link com.sweprep.backend.language.LanguageAdapter#languageId()} of the adapter
     * whose generated harness it is meant to execute - {@code RunnerRegistry} routes on
     * this the same way {@code LanguageAdapterRegistry} routes on the adapter's id.
     */
    String languageId();

    ExecutionResult execute(ExecutionRequest request);
}
