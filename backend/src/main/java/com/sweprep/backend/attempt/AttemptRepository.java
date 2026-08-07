package com.sweprep.backend.attempt;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persists and queries {@link Attempt}s, so practice history survives a restart
 * (issue #15). Plain Spring JDBC over the {@code attempt} table - the stack uses
 * {@code spring-boot-starter-jdbc}, not JPA, and Flyway owns the schema
 * (migration {@code V3__attempts.sql}), never Hibernate ddl-auto.
 */
@Repository
public class AttemptRepository {

    private final JdbcClient jdbc;

    public AttemptRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts a new attempt exactly as given. */
    public void insert(Attempt attempt) {
        jdbc.sql(
                        """
                        INSERT INTO attempt (
                            id, user_id, exercise_id, exercise_title, domain, form,
                            outcome, started_at, ended_at, hints_taken,
                            failing_case_revealed, complexity_claim, measured_complexity,
                            complexity_claim_correct)
                        VALUES (
                            :id, :userId, :exerciseId, :exerciseTitle, :domain, :form,
                            :outcome, :startedAt, :endedAt, :hintsTaken,
                            :failingCaseRevealed, :complexityClaim, :measuredComplexity,
                            :complexityClaimCorrect)
                        """)
                .param("id", attempt.id())
                .param("userId", attempt.userId())
                .param("exerciseId", attempt.exerciseId())
                .param("exerciseTitle", attempt.exerciseTitle())
                .param("domain", attempt.domain())
                .param("form", attempt.form())
                .param("outcome", attempt.outcome().name())
                .param("startedAt", toTimestamp(attempt.startedAt()))
                .param("endedAt", toTimestamp(attempt.endedAt()))
                .param("hintsTaken", attempt.hintsTaken())
                .param("failingCaseRevealed", attempt.failingCaseRevealed())
                .param("complexityClaim", attempt.complexityClaim())
                .param("measuredComplexity", attempt.measuredComplexity())
                .param("complexityClaimCorrect", attempt.complexityClaimCorrect())
                .update();
    }

    /**
     * Rewrites the mutable columns of an existing attempt (outcome, ended_at and the
     * help/complexity fields). Identity and the snapshotted exercise columns never
     * change, so they are not touched.
     */
    public void update(Attempt attempt) {
        jdbc.sql(
                        """
                        UPDATE attempt SET
                            outcome = :outcome,
                            ended_at = :endedAt,
                            hints_taken = :hintsTaken,
                            failing_case_revealed = :failingCaseRevealed,
                            complexity_claim = :complexityClaim,
                            measured_complexity = :measuredComplexity,
                            complexity_claim_correct = :complexityClaimCorrect
                        WHERE id = :id
                        """)
                .param("id", attempt.id())
                .param("outcome", attempt.outcome().name())
                .param("endedAt", toTimestamp(attempt.endedAt()))
                .param("hintsTaken", attempt.hintsTaken())
                .param("failingCaseRevealed", attempt.failingCaseRevealed())
                .param("complexityClaim", attempt.complexityClaim())
                .param("measuredComplexity", attempt.measuredComplexity())
                .param("complexityClaimCorrect", attempt.complexityClaimCorrect())
                .update();
    }

    public Optional<Attempt> findById(UUID id) {
        return jdbc.sql("SELECT * FROM attempt WHERE id = :id")
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    /** Every attempt for one user, newest first - the history query. */
    public List<Attempt> findByUser(UUID userId) {
        return jdbc.sql("SELECT * FROM attempt WHERE user_id = :userId ORDER BY started_at DESC")
                .param("userId", userId)
                .query(MAPPER)
                .list();
    }

    private static java.sql.Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : java.sql.Timestamp.from(instant);
    }

    private static Instant toInstant(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static final RowMapper<Attempt> MAPPER = AttemptRepository::mapRow;

    private static Attempt mapRow(ResultSet rs, int rowNum) throws SQLException {
        Object claimCorrect = rs.getObject("complexity_claim_correct");
        return new Attempt(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("exercise_id"),
                rs.getString("exercise_title"),
                rs.getString("domain"),
                rs.getString("form"),
                AttemptOutcome.valueOf(rs.getString("outcome")),
                toInstant(rs.getTimestamp("started_at")),
                toInstant(rs.getTimestamp("ended_at")),
                rs.getInt("hints_taken"),
                rs.getBoolean("failing_case_revealed"),
                rs.getString("complexity_claim"),
                rs.getString("measured_complexity"),
                claimCorrect == null ? null : (Boolean) claimCorrect);
    }
}
