package com.sweprep.backend.attempt;

import com.sweprep.backend.grader.Verdict;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persists and queries {@link Submission}s. Plain Spring JDBC over the
 * {@code submission} table (migration {@code V3__attempts.sql}); every submission is
 * kept so the scheduler (issue #8) can see how many tries a sitting took.
 */
@Repository
public class SubmissionRepository {

    private final JdbcClient jdbc;

    public SubmissionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Submission submission) {
        jdbc.sql(
                        """
                        INSERT INTO submission (
                            id, attempt_id, submitted_at, response, outcome, passed, total, detail,
                            runtime_millis)
                        VALUES (
                            :id, :attemptId, :submittedAt, :response, :outcome, :passed, :total, :detail,
                            :runtimeMillis)
                        """)
                .param("id", submission.id())
                .param("attemptId", submission.attemptId())
                .param("submittedAt", java.sql.Timestamp.from(submission.submittedAt()))
                .param("response", submission.response())
                .param("outcome", submission.outcome().name())
                .param("passed", submission.passed())
                .param("total", submission.total())
                .param("detail", submission.detail())
                .param("runtimeMillis", submission.runtimeMillis())
                .update();
    }

    /** How many times Run was pressed in this attempt. */
    public int countByAttempt(UUID attemptId) {
        return jdbc.sql("SELECT count(*) FROM submission WHERE attempt_id = :attemptId")
                .param("attemptId", attemptId)
                .query(Integer.class)
                .single();
    }

    /**
     * Submission counts for a set of attempts in one query, so reading history does
     * not re-count per row. Attempts with no submissions are simply absent from the
     * map; the caller defaults them to zero.
     */
    public Map<UUID, Integer> countsForAttempts(List<UUID> attemptIds) {
        if (attemptIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Integer> counts = new HashMap<>();
        jdbc.sql(
                        """
                        SELECT attempt_id, count(*) AS n FROM submission
                        WHERE attempt_id IN (:attemptIds)
                        GROUP BY attempt_id
                        """)
                .param("attemptIds", attemptIds)
                .query((ResultSet rs, int rowNum) ->
                        counts.put(rs.getObject("attempt_id", UUID.class), rs.getInt("n")))
                .list();
        return counts;
    }

    /**
     * The exercise id and picked response of every wrong Choice-style submission this
     * user made, so the confusion relation (issue #39) can be derived from which
     * distractor was chosen without a new column - the schema already stores the picked
     * response. A {@code FAILED} outcome is the wrong-answer signal; other outcomes
     * ({@code COMPILE_ERROR}, {@code TIMEOUT}, {@code ERROR}) are execution problems, not
     * a chosen distractor, and are excluded. Whether a given row was actually a Choice
     * response (rather than code or free text) is resolved by the caller against the
     * catalog, since the exercise's response kind lives in content, not this table.
     */
    public List<FailedResponse> failedResponses(UUID userId) {
        return jdbc.sql(
                        """
                        SELECT a.exercise_id AS exercise_id, s.response AS response
                        FROM submission s
                        JOIN attempt a ON s.attempt_id = a.id
                        WHERE a.user_id = :userId AND s.outcome = 'FAILED'
                        """)
                .param("userId", userId)
                .query((ResultSet rs, int rowNum) ->
                        new FailedResponse(rs.getString("exercise_id"), rs.getString("response")))
                .list();
    }

    /**
     * One wrong submission reduced to the two fields the confusion derivation needs: the
     * exercise it was for and the response the solver picked (a distractor label, for a
     * Choice rep).
     */
    public record FailedResponse(String exerciseId, String response) {}

    /** Every submission in an attempt, oldest first. */
    public List<Submission> findByAttempt(UUID attemptId) {
        return jdbc.sql(
                        "SELECT * FROM submission WHERE attempt_id = :attemptId ORDER BY submitted_at")
                .param("attemptId", attemptId)
                .query(MAPPER)
                .list();
    }

    private static final RowMapper<Submission> MAPPER = SubmissionRepository::mapRow;

    private static Submission mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Submission(
                rs.getObject("id", UUID.class),
                rs.getObject("attempt_id", UUID.class),
                rs.getTimestamp("submitted_at").toInstant(),
                rs.getString("response"),
                Verdict.Outcome.valueOf(rs.getString("outcome")),
                rs.getInt("passed"),
                rs.getInt("total"),
                rs.getString("detail"),
                rs.getLong("runtime_millis"));
    }
}
