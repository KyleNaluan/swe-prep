package com.sweprep.backend.grader;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sweprep.backend.exercise.Exercise;
import com.sweprep.backend.sql.SqlExecutionRequest;
import com.sweprep.backend.sql.SqlExecutionResult;
import com.sweprep.backend.sql.SqlRunner;
import com.sweprep.backend.testsupport.Fixtures;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Proves {@code SqlQueryGrader} judges a result-set exercise entirely through {@link
 * SqlRunner} - a fake stands in here, exactly the {@code Grader}/{@code Runner} split
 * {@code TestCaseGraderTest} proves for the language seam (decision issue #6) - and that
 * a wrong answer discloses only a bare row count (issue #25's minimal failure signal),
 * never which rows differed.
 */
class SqlQueryGraderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Exercise exercise = Fixtures.sqlTopCustomers();

    @Test
    void supportsOnlyResultSetGradedExercises() {
        SqlQueryGrader grader = new SqlQueryGrader(fakeRunner(rows("[[1,\"Alice\"],[2,\"Bob\"]]")), timeout());

        assertThat(grader.supports(exercise)).isTrue();
        assertThat(grader.supports(Fixtures.pairInAnyOrder())).isFalse();
    }

    @Test
    void aMatchingResultSetPasses() {
        SqlQueryGrader grader = new SqlQueryGrader(fakeRunner(rows("[[2,\"Bob\"],[1,\"Alice\"]]")), timeout());

        Verdict verdict = grader.grade(exercise, "select id, name from customers");

        // Row order is ignored by default (issue #25) - the rows above arrive reversed.
        assertThat(verdict.outcome()).isEqualTo(Verdict.Outcome.PASSED);
        assertThat(verdict.passed()).isEqualTo(2);
        assertThat(verdict.total()).isEqualTo(2);
    }

    @Test
    void aWrongRowCountFailsAndDisclosesOnlyTheCount() {
        SqlQueryGrader grader = new SqlQueryGrader(fakeRunner(rows("[[1,\"Alice\"]]")), timeout());

        Verdict verdict = grader.grade(exercise, "select id, name from customers limit 1");

        assertThat(verdict.outcome()).isEqualTo(Verdict.Outcome.FAILED);
        // The minimal failure signal is a row count, never which rows were wrong or how
        // (issues #16/#5, #25): passed/total carry only the bare actual-vs-expected count.
        assertThat(verdict.passed()).isEqualTo(1);
        assertThat(verdict.total()).isEqualTo(2);
        assertThat(verdict.detail()).isEmpty();
    }

    @Test
    void theSameRowCountWithWrongContentStillFails() {
        // A coincidence the passed==total shortcut used elsewhere cannot rely on here:
        // Verdict.rows decides the outcome from the match itself, not from the count.
        SqlQueryGrader grader = new SqlQueryGrader(fakeRunner(rows("[[9,\"Carol\"],[8,\"Dave\"]]")), timeout());

        Verdict verdict = grader.grade(exercise, "select id, name from customers");

        assertThat(verdict.outcome()).isEqualTo(Verdict.Outcome.FAILED);
        assertThat(verdict.passed()).isEqualTo(2);
        assertThat(verdict.total()).isEqualTo(2);
    }

    @Test
    void aRefusedWriteIsReportedAsACompileErrorVerdict() {
        SqlQueryGrader grader = new SqlQueryGrader(
                fakeRunner(SqlExecutionResult.queryError(
                        "ERROR: cannot execute DROP TABLE in a read-only transaction")),
                timeout());

        Verdict verdict = grader.grade(exercise, "drop table customers");

        assertThat(verdict.outcome()).isEqualTo(Verdict.Outcome.COMPILE_ERROR);
        assertThat(verdict.detail()).contains("read-only transaction");
    }

    @Test
    void aTimeoutIsReported() {
        SqlQueryGrader grader = new SqlQueryGrader(
                fakeRunner(SqlExecutionResult.timeout("Execution timed out after 10s")), timeout());

        Verdict verdict = grader.grade(exercise, "select pg_sleep(60)");

        assertThat(verdict.outcome()).isEqualTo(Verdict.Outcome.TIMEOUT);
    }

    private static Duration timeout() {
        return Duration.ofSeconds(10);
    }

    private static JsonNode rows(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static SqlRunner fakeRunner(JsonNode rows) {
        return fakeRunner(SqlExecutionResult.completed(rows));
    }

    private static SqlRunner fakeRunner(SqlExecutionResult result) {
        return new SqlRunner() {
            @Override
            public SqlExecutionResult execute(SqlExecutionRequest request) {
                return result;
            }
        };
    }
}
