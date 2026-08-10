package com.sweprep.backend.sql;

/**
 * Executes one submitted SQL query and reports what happened - the SQL domain's
 * "embedded-equivalent Runner" (issue #25, decision issue #10). It plays exactly the same
 * architectural role the language-neutral {@code Runner} interface plays for code: it only
 * executes, never decides pass or fail, so {@code SqlQueryGrader} owns the verdict exactly
 * as {@code TestCaseGrader} owns its own over {@code Runner}.
 *
 * <p>It is a separate interface rather than an implementation of {@code
 * com.sweprep.backend.runner.Runner} because the two shapes do not fit one contract: that
 * interface compiles and runs a program from source files to an exit code and captured
 * output, where this executes one query string against a fixture database and reports back
 * a result set. Keeping them distinct is what lets a second domain add its own runner-like
 * seam without stretching the first one to cover a shape it was never designed for.
 */
public interface SqlRunner {

    SqlExecutionResult execute(SqlExecutionRequest request);
}
