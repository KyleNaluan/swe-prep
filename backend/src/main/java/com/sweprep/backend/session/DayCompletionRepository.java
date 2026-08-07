package com.sweprep.backend.session;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persists and queries {@link DayCompletion}s, so "the day is complete" survives a
 * restart and is read cheaply on every app open (issue #19). Plain Spring JDBC over the
 * {@code day_completion} table, Flyway-owned (migration {@code V6__day_completion.sql}),
 * matching the {@code attempt} repository's shape rather than reaching for JPA.
 */
@Repository
public class DayCompletionRepository {

    private final JdbcClient jdbc;

    public DayCompletionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Marks a day complete, idempotently: the primary key on {@code (user_id,
     * completed_on)} means a second warm-up on the same day - or re-opening the app -
     * inserts nothing and never moves {@code completed_at}. So the day is complete from
     * the first warm-up onward, and nothing that follows it can change that.
     */
    public void markComplete(UUID userId, LocalDate date, Instant at) {
        jdbc.sql(
                        """
                        INSERT INTO day_completion (user_id, completed_on, completed_at)
                        VALUES (:userId, :date, :at)
                        ON CONFLICT (user_id, completed_on) DO NOTHING
                        """)
                .param("userId", userId)
                .param("date", java.sql.Date.valueOf(date))
                .param("at", Timestamp.from(at))
                .update();
    }

    /** The completion for one user on one day, if that day is complete. */
    public Optional<DayCompletion> find(UUID userId, LocalDate date) {
        return jdbc.sql(
                        "SELECT completed_on, completed_at FROM day_completion "
                                + "WHERE user_id = :userId AND completed_on = :date")
                .param("userId", userId)
                .param("date", java.sql.Date.valueOf(date))
                .query((rs, rowNum) -> new DayCompletion(
                        userId,
                        rs.getObject("completed_on", LocalDate.class),
                        rs.getTimestamp("completed_at").toInstant()))
                .optional();
    }

    /**
     * Every completed day for one user, newest first. Single-user forever (issue #1),
     * so this is a small set the streak walk consumes in memory rather than pushing a
     * recursive gaps-and-islands query into SQL.
     */
    public List<LocalDate> completedDates(UUID userId) {
        return jdbc.sql(
                        "SELECT completed_on FROM day_completion "
                                + "WHERE user_id = :userId ORDER BY completed_on DESC")
                .param("userId", userId)
                .query((rs, rowNum) -> rs.getObject("completed_on", LocalDate.class))
                .list();
    }
}
