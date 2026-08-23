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
                            complexity_claim, measured_complexity, complexity_claim_correct,
                            solution_seen)
                        VALUES (
                            :id, :userId, :exerciseId, :exerciseTitle, :domain, :form,
                            :outcome, :startedAt, :endedAt, :hintsTaken,
                            :failingCaseRevealed, :revealHypothesis, :explanationRequested,
                            :complexityClaim, :measuredComplexity, :complexityClaimCorrect,
                            :solutionSeen)
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
                .param("solutionSeen", attempt.solutionSeen())
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
                            complexity_claim_correct = :complexityClaimCorrect,
                            solution_seen = :solutionSeen
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
                .param("solutionSeen", attempt.solutionSeen())
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
                            complexity_claim, measured_complexity, complexity_claim_correct,
                            solution_seen)
                        VALUES (
                            :id, :userId, :exerciseId, :exerciseTitle, :domain, :form,
                            :outcome, :startedAt, :endedAt, :hintsTaken,
                            :failingCaseRevealed, :revealHypothesis, :explanationRequested,
                            :complexityClaim, :measuredComplexity, :complexityClaimCorrect,
                            :solutionSeen)
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
                .param("solutionSeen", attempt.solutionSeen())
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
     * The distinct ids of every {@code CHALLENGE}-form exercise this user has ever solved
     * with no help taken - no hint climbed, no failing case revealed, and no reference
     * solution seen before passing (issues #16/#45/#82). This is the "solved-cold"
     * objective competence axis the readiness picture reads: like {@link
     * com.sweprep.backend.attempt.SubmissionRepository#cleanPassInstants}'s {@code
     * outcome = 'PASSED'} boundary, the query alone keeps it a machine-verdict signal - a
     * solve that needed help still counts as solved, but not as solved cold.
     *
     * <p>{@code solution_seen = false} is what gives issue #82's "excluded until a later
     * clean pass" its exact meaning here: this is an <em>exists-any</em> query, so an
     * exercise with one tainted {@code SOLVED} attempt (solution seen pre-pass) and a
     * later, genuinely clean {@code SOLVED} attempt still counts - the later clean row
     * satisfies every condition on its own, with no special-casing needed for "later".
     */
    public java.util.Set<String> solvedColdExerciseIds(UUID userId) {
        return new java.util.HashSet<>(
                jdbc.sql(
                                """
                                SELECT DISTINCT exercise_id FROM attempt
                                WHERE user_id = :userId AND form = 'CHALLENGE' AND outcome = 'SOLVED'
                                  AND hints_taken = 0 AND failing_case_revealed = false
                                  AND solution_seen = false
                                """)
                        .param("userId", userId)
                        .query(String.class)
                        .list());
    }

    /**
     * The distinct ids of every exercise this user has completed as a self-check
     * "explain in your own words" item (issue #41: an attempt ending {@code EXPLAINED}).
     * The readiness picture (#45) counts these separately, as "explained N concepts" -
     * never folded into an objective competence axis, since a self-rating is not a
     * machine verdict.
     */
    public java.util.Set<String> explainedExerciseIds(UUID userId) {
        return new java.util.HashSet<>(
                jdbc.sql(
                                "SELECT DISTINCT exercise_id FROM attempt "
                                        + "WHERE user_id = :userId AND outcome = 'EXPLAINED'")
                        .param("userId", userId)
                        .query(String.class)
                        .list());
    }

    /**
     * One terminal {@code REP}-form attempt's outcome - the raw material {@code RepDueService}
     * (issue #20) reduces to a spaced-repetition {@code Review}. {@code solved} is the
     * correctness signal; {@code explanationRequested} is the "asked why" confidence signal
     * (issue #51) that makes an otherwise-correct review weaker; {@code solutionSeen} is the
     * reference-solution-seen-before-passing signal (issue #82) that makes it weaker still.
     */
    public record RepReview(
            String exerciseId, Instant endedAt, boolean solved, boolean explanationRequested, boolean solutionSeen) {}

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
                        SELECT exercise_id, ended_at, outcome, explanation_requested, solution_seen
                        FROM attempt
                        WHERE user_id = :userId AND form = 'REP' AND outcome IN ('SOLVED', 'ABANDONED')
                        ORDER BY ended_at
                        """)
                .param("userId", userId)
                .query((ResultSet rs, int rowNum) -> new RepReview(
                        rs.getString("exercise_id"),
                        rs.getTimestamp("ended_at").toInstant(),
                        "SOLVED".equals(rs.getString("outcome")),
                        rs.getBoolean("explanation_requested"),
                        rs.getBoolean("solution_seen")))
                .list();
    }

    /**
     * One terminal {@code CHALLENGE}-form attempt's outcome and help/complexity fields -
     * the raw material {@link com.sweprep.backend.challenge.ChallengeService} (issue #21)
     * reduces to a {@link com.sweprep.backend.scheduler.ChallengeQuality} score. Carries
     * the attempt id (not just the exercise id) because the 0-5 derivation also needs the
     * submission count, which lives per-attempt in {@link SubmissionRepository} and is
     * batched in separately rather than joined here. Carries the raw {@link AttemptOutcome}
     * rather than a {@code solved} boolean, because {@link #challengeReviews} returns three
     * distinct outcomes (see there) and a caller needs to tell them apart.
     */
    public record ChallengeAttemptRow(
            UUID attemptId,
            String exerciseId,
            Instant endedAt,
            AttemptOutcome outcome,
            int hintsTaken,
            boolean failingCaseRevealed,
            Boolean complexityClaimCorrect,
            boolean solutionSeen) {}

    /**
     * Every terminal {@code CHALLENGE}-form attempt this user has ever ended, across every
     * exercise - the challenge priority scorer's (issue #21) raw material, the same shape
     * {@link #repReviews} plays for the rep due-date queue. {@code SOLVED} and {@code
     * ABANDONED} are the two outcomes a machine-graded challenge reaches; a self-check
     * challenge (issue #41, produce-then-reveal-then-self-rate) reaches a third, {@code
     * EXPLAINED}, and is included here too - excluding it would leave such a challenge
     * permanently invisible to the scorer (always {@code reviews.isEmpty()}), silently
     * defeating the minimum-interval floor for it. An attempt still {@code IN_PROGRESS} is
     * not yet a completed review and stays excluded, the same structural boundary {@link
     * #repReviews} enforces.
     */
    public List<ChallengeAttemptRow> challengeReviews(UUID userId) {
        return jdbc.sql(
                        """
                        SELECT id, exercise_id, ended_at, outcome, hints_taken,
                               failing_case_revealed, complexity_claim_correct, solution_seen
                        FROM attempt
                        WHERE user_id = :userId AND form = 'CHALLENGE'
                          AND outcome IN ('SOLVED', 'ABANDONED', 'EXPLAINED')
                        ORDER BY ended_at
                        """)
                .param("userId", userId)
                .query((ResultSet rs, int rowNum) -> {
                    Object claimCorrect = rs.getObject("complexity_claim_correct");
                    return new ChallengeAttemptRow(
                            rs.getObject("id", UUID.class),
                            rs.getString("exercise_id"),
                            rs.getTimestamp("ended_at").toInstant(),
                            AttemptOutcome.valueOf(rs.getString("outcome")),
                            rs.getInt("hints_taken"),
                            rs.getBoolean("failing_case_revealed"),
                            claimCorrect == null ? null : (Boolean) claimCorrect,
                            rs.getBoolean("solution_seen"));
                })
                .list();
    }

    /**
     * The earliest sitting this user ever opened with each {@code CHALLENGE}-form
     * exercise, whatever its outcome - the raw material the weekly new-introduction cap
     * (issue #21) counts against. Like {@link #attemptedExerciseIds}, any sitting counts
     * as having met the exercise; unlike it, this keeps the date rather than collapsing
     * to a set, since the cap only cares about introductions that fell within the current
     * week.
     */
    public java.util.Map<String, Instant> firstChallengeAttemptDates(UUID userId) {
        java.util.Map<String, Instant> firstAttempts = new java.util.HashMap<>();
        jdbc.sql(
                        """
                        SELECT exercise_id, MIN(started_at) AS first_started
                        FROM attempt
                        WHERE user_id = :userId AND form = 'CHALLENGE'
                        GROUP BY exercise_id
                        """)
                .param("userId", userId)
                .query((ResultSet rs, int rowNum) -> firstAttempts.put(
                        rs.getString("exercise_id"), rs.getTimestamp("first_started").toInstant()))
                .list();
        return firstAttempts;
    }

    /**
     * The most recent sitting this user opened with each exercise, whatever its form or
     * outcome - the "last touched" signal the readiness picture's staleness axis (issue
     * #22) reduces to a per-topic gap in days. Unlike {@link #attemptedExerciseIds} this
     * keeps the date rather than collapsing to a set of ids, since staleness is about
     * how long ago, not merely whether.
     */
    public java.util.Map<String, Instant> lastAttemptDates(UUID userId) {
        java.util.Map<String, Instant> lastAttempts = new java.util.HashMap<>();
        jdbc.sql(
                        """
                        SELECT exercise_id, MAX(started_at) AS last_started
                        FROM attempt
                        WHERE user_id = :userId
                        GROUP BY exercise_id
                        """)
                .param("userId", userId)
                .query((ResultSet rs, int rowNum) -> lastAttempts.put(
                        rs.getString("exercise_id"), rs.getTimestamp("last_started").toInstant()))
                .list();
        return lastAttempts;
    }

    /**
     * The end instant of every {@code CHALLENGE}-form attempt this user has ever solved -
     * the "extra tier engaged" signal the streak repair mechanic (issue #22, decision #7
     * item 5) recognises as a double session: the required warm-up plus a solved
     * challenge on the same calendar day.
     */
    public List<Instant> challengeSolvedInstants(UUID userId) {
        return jdbc.sql(
                        """
                        SELECT ended_at FROM attempt
                        WHERE user_id = :userId AND form = 'CHALLENGE' AND outcome = 'SOLVED'
                        """)
                .param("userId", userId)
                .query((ResultSet rs, int rowNum) -> rs.getTimestamp("ended_at").toInstant())
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
                claimCorrect == null ? null : (Boolean) claimCorrect,
                rs.getBoolean("solution_seen"));
    }
}
