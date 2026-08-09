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
                            failing_case_revealed, reveal_hypothesis, explanation_requested,
                            complexity_claim, measured_complexity, complexity_claim_correct)
                        VALUES (
                            :id, :userId, :exerciseId, :exerciseTitle, :domain, :form,
                            :outcome, :startedAt, :endedAt, :hintsTaken,
                            :failingCaseRevealed, :revealHypothesis, :explanationRequested,
                            :complexityClaim, :measuredComplexity, :complexityClaimCorrect)
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
                .param("revealHypothesis", attempt.revealHypothesis())
                .param("explanationRequested", attempt.explanationRequested())
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
                            reveal_hypothesis = :revealHypothesis,
                            explanation_requested = :explanationRequested,
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
                .param("revealHypothesis", attempt.revealHypothesis())
                .param("explanationRequested", attempt.explanationRequested())
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

    /**
     * Reads an attempt for a state-changing operation, taking a row lock so concurrent
     * mutations of the same attempt serialise. A racing abandon can then never clobber a
     * sitting another transaction has already solved: it blocks, re-reads the committed
     * outcome, and is rejected as already ended rather than overwriting it.
     */
    public Optional<Attempt> findByIdForUpdate(UUID id) {
        return jdbc.sql("SELECT * FROM attempt WHERE id = :id FOR UPDATE")
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

    /**
     * The distinct ids of every exercise this user has ever opened a sitting with,
     * whatever its outcome. This is the "problems attempted" set the warm-up selector
     * gates derived reps on (issue #18): a rep built from a problem is only served once
     * that problem has been met, and any sitting - even an abandoned one - counts as
     * having met it.
     */
    public java.util.Set<String> attemptedExerciseIds(UUID userId) {
        return new java.util.HashSet<>(
                jdbc.sql("SELECT DISTINCT exercise_id FROM attempt WHERE user_id = :userId")
                        .param("userId", userId)
                        .query(String.class)
                        .list());
    }

    /**
     * Records a Lesson read idempotently per (user, lesson): the first read inserts the given
     * {@code READ} attempt, and a re-read refreshes the one existing record's {@code ended_at}
     * instead of appending a duplicate (issue #40). This is a single atomic upsert on the partial
     * unique index {@code attempt_one_read_per_user_exercise} (Flyway {@code V7}), so two racing
     * first-reads can never both insert - the DB enforces the one-row invariant, not a read-then-write
     * that a concurrent read could slip past. Returns the persisted row: the freshly inserted attempt
     * on a first read, or the pre-existing one (its original id, refreshed timestamp) on a re-read.
     */
    public Attempt upsertRead(Attempt attempt) {
        return jdbc.sql(
                        """
                        INSERT INTO attempt (
                            id, user_id, exercise_id, exercise_title, domain, form,
                            outcome, started_at, ended_at, hints_taken,
                            failing_case_revealed, reveal_hypothesis, explanation_requested,
                            complexity_claim, measured_complexity, complexity_claim_correct)
                        VALUES (
                            :id, :userId, :exerciseId, :exerciseTitle, :domain, :form,
                            :outcome, :startedAt, :endedAt, :hintsTaken,
                            :failingCaseRevealed, :revealHypothesis, :explanationRequested,
                            :complexityClaim, :measuredComplexity, :complexityClaimCorrect)
                        ON CONFLICT (user_id, exercise_id) WHERE outcome = 'READ'
                        DO UPDATE SET ended_at = EXCLUDED.ended_at
                        RETURNING *
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
                .param("revealHypothesis", attempt.revealHypothesis())
                .param("explanationRequested", attempt.explanationRequested())
                .param("complexityClaim", attempt.complexityClaim())
                .param("measuredComplexity", attempt.measuredComplexity())
                .param("complexityClaimCorrect", attempt.complexityClaimCorrect())
                .query(MAPPER)
                .single();
    }

    /**
     * The distinct ids of every Lesson this user has read (an attempt with outcome
     * {@code READ}). Reading a Lesson seeds its Checks into the warm-up even when their
     * family is inactive - the reachability hinge of the family filter (issue #40, design
     * revision t3 section 2.2) - so the warm-up build maps these lesson ids to their Checks.
     */
    public java.util.Set<String> readLessonIds(UUID userId) {
        return new java.util.HashSet<>(
                jdbc.sql(
                                "SELECT DISTINCT exercise_id FROM attempt "
                                        + "WHERE user_id = :userId AND outcome = 'READ'")
                        .param("userId", userId)
                        .query(String.class)
                        .list());
    }

    /**
     * One terminal {@code REP}-form attempt's outcome - the raw material {@code RepDueService}
     * (issue #20) reduces to a spaced-repetition {@code Review}. {@code solved} is the
     * correctness signal; {@code explanationRequested} is the "asked why" confidence signal
     * (issue #51) that makes an otherwise-correct review weaker.
     */
    public record RepReview(String exerciseId, Instant endedAt, boolean solved, boolean explanationRequested) {}

    /**
     * Every terminal {@code REP}-form attempt this user has ever ended, across every exercise -
     * the due-date scheduler's (issue #20) raw material. Only {@code SOLVED} (correct) and
     * {@code ABANDONED} (not solved) are terminal outcomes a rep can reach; a self-check rep
     * ends {@code EXPLAINED} instead, which this {@code outcome IN (...)} filter structurally
     * excludes with no separate form or grading-kind check - the same "the query alone enforces
     * the boundary" shape as {@link com.sweprep.backend.attempt.SubmissionRepository#cleanPassInstants}.
     * An attempt still {@code IN_PROGRESS} is not yet a completed review and is excluded too.
     */
    public List<RepReview> repReviews(UUID userId) {
        return jdbc.sql(
                        """
                        SELECT exercise_id, ended_at, outcome, explanation_requested
                        FROM attempt
                        WHERE user_id = :userId AND form = 'REP' AND outcome IN ('SOLVED', 'ABANDONED')
                        ORDER BY ended_at
                        """)
                .param("userId", userId)
                .query((ResultSet rs, int rowNum) -> new RepReview(
                        rs.getString("exercise_id"),
                        rs.getTimestamp("ended_at").toInstant(),
                        "SOLVED".equals(rs.getString("outcome")),
                        rs.getBoolean("explanation_requested")))
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
                rs.getString("reveal_hypothesis"),
                rs.getBoolean("explanation_requested"),
                rs.getString("complexity_claim"),
                rs.getString("measured_complexity"),
                claimCorrect == null ? null : (Boolean) claimCorrect);
    }
}
