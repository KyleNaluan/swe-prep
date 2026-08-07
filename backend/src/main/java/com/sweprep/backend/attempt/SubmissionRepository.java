package com.sweprep.backend.attempt;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    /**
     * The instant of every <em>clean machine-verdict pass</em> this user earned on one
     * exercise, oldest first - the raw material the successive-relearning criterion (issue
     * #38) reduces to spaced sessions. Only a {@code PASSED} outcome is a clean pass; every
     * other outcome is either a wrong answer or an execution problem, not a retrieval to
     * criterion, and is excluded.
     *
     * <p>This {@code outcome = 'PASSED'} filter is the <b>structural</b> boundary that keeps
     * read and self-check items out of the objective competence signal (design revision t3
     * section 4.1): a self-check is never machine-graded - {@code SelfCheckGrader.grade}
     * throws before any submission is inserted - and a lesson read is never graded at all, so
     * neither can ever produce a {@code PASSED} row for this query to return. The exclusion is
     * therefore enforced by the grade path and the query together, not by a caller remembering
     * to filter by content kind (which this table does not even store).
     */
    public List<Instant> cleanPassInstants(UUID userId, String exerciseId) {
        return jdbc.sql(
                        """
                        SELECT s.submitted_at AS submitted_at
                        FROM submission s
                        JOIN attempt a ON s.attempt_id = a.id
                        WHERE a.user_id = :userId
                          AND a.exercise_id = :exerciseId
                          AND s.outcome = 'PASSED'
                        ORDER BY s.submitted_at
                        """)
                .param("userId", userId)
                .param("exerciseId", exerciseId)
                .query((ResultSet rs, int rowNum) -> rs.getTimestamp("submitted_at").toInstant())
                .list();
    }

    /**
     * Every user's clean machine-verdict passes grouped by exercise, so the scheduler (issue
     * #8) can evaluate the whole catalog's learned state in one query rather than one round
     * trip per exercise. Same {@code outcome = 'PASSED'} boundary as {@link #cleanPassInstants}
     * - read and self-check items are structurally absent. Exercises with no clean pass are
     * simply missing from the map; the caller reads them as {@code NEW}.
     */
    public Map<String, List<Instant>> cleanPassInstantsByExercise(UUID userId) {
        Map<String, List<Instant>> byExercise = new HashMap<>();
        jdbc.sql(
                        """
                        SELECT a.exercise_id AS exercise_id, s.submitted_at AS submitted_at
                        FROM submission s
                        JOIN attempt a ON s.attempt_id = a.id
                        WHERE a.user_id = :userId AND s.outcome = 'PASSED'
                        ORDER BY s.submitted_at
                        """)
                .param("userId", userId)
                .query((ResultSet rs, int rowNum) -> byExercise
                        .computeIfAbsent(rs.getString("exercise_id"), key -> new ArrayList<>())
                        .add(rs.getTimestamp("submitted_at").toInstant()))
                .list();
        return byExercise;
    }

    /** One submission by id, or empty when there is none. */
    public Optional<Submission> findById(UUID id) {
        return jdbc.sql("SELECT * FROM submission WHERE id = :id")
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    /**
     * Records a self-check's self-rating on its already-committed submission, in the
     * existing {@code detail} column (design revision t3, section 5 - no migration). The
     * submission was inserted when the model answer was revealed, freezing the produced
     * text before the learner saw the answer; this only stamps the rating they then chose.
     * The {@code SELF_RATED} outcome is untouched, so the row stays structurally invisible
     * to the objective competence signal.
     */
    public void recordSelfRating(UUID id, String rating) {
        jdbc.sql("UPDATE submission SET detail = :detail WHERE id = :id")
                .param("id", id)
                .param("detail", rating)
                .update();
    }

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
                SubmissionOutcome.valueOf(rs.getString("outcome")),
                rs.getInt("passed"),
                rs.getInt("total"),
                rs.getString("detail"),
                rs.getLong("runtime_millis"));
    }
}
