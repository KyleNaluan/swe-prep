package com.sweprep.backend.grader;

import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.exercise.Grading;
import com.sweprep.backend.sql.SqlExecutionRequest;
import com.sweprep.backend.sql.SqlExecutionResult;
import com.sweprep.backend.sql.SqlRunner;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Grades a SQL submission by applying its exercise's fixture, running the query through
 * {@link SqlRunner} and comparing the returned result set against {@link
 * Grading.ResultSet#expected()} - the proof issue #25 asks for, that a second domain lands
 * on the exact same {@code Grader}/{@code Runner} seam (decision issue #6) with no change
 * to either interface. It handles exercises whose grading spec is {@link
 * Grading.ResultSet}; nothing here is SQL-specific beyond delegating to {@link SqlRunner} -
 * comparison itself is the shared {@code Comparison} rule every grading spec already uses.
 *
 * <p>The verdict withholds by default like every other grader (issues #16/#5): on a
 * mismatch the disclosed signal is only {@link Verdict#rows}' bare row count, actual versus
 * expected, never which rows differed or how. There is nothing further to reveal on
 * request - {@link #firstFailingCase} is left at its default (empty), since the row count
 * already <em>is</em> the minimal failure signal this domain declares, not a summary of a
 * richer one being held back.
 */
@Component
public class SqlQueryGrader implements Grader {

    private final SqlRunner runner;
    private final Duration timeout;

    public SqlQueryGrader(SqlRunner runner, @Value("${sweprep.grader.timeout:PT10S}") Duration timeout) {
        this.runner = runner;
        this.timeout = timeout;
    }

    @Override
    public boolean supports(Exercise exercise) {
        return exercise.grading() instanceof Grading.ResultSet;
    }

    @Override
    public Verdict grade(Exercise exercise, String submission) {
        Grading.ResultSet spec = (Grading.ResultSet) exercise.grading();
        long startNanos = System.nanoTime();
        SqlExecutionResult result = runner.execute(
                new SqlExecutionRequest(spec.fixture(), submission == null ? "" : submission, timeout));
        long millis = (System.nanoTime() - startNanos) / 1_000_000L;

        int total = spec.expected().size();
        Verdict verdict = switch (result.outcome()) {
            case QUERY_ERROR -> Verdict.compileError(result.errorMessage());
            case TIMEOUT -> Verdict.timeout(
                    total, "Execution timed out after " + timeout.toSeconds() + "s (possible runaway query)");
            case COMPLETED -> {
                boolean matches = spec.comparison().matches(spec.expected(), result.rows());
                yield Verdict.rows(matches, result.rows().size(), total);
            }
        };
        return verdict.withRuntime(millis);
    }
}
