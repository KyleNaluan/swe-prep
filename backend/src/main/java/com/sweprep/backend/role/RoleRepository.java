package com.sweprep.backend.role;

import com.sweprep.backend.exercise.Family;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists which families a user has turned on (issue #40), so the answer to "what am I preparing
 * for?" survives a restart and is read on every warm-up build. Plain Spring JDBC over the
 * {@code active_family} table (Flyway migration {@code V7__role_families.sql}), matching the
 * {@code attempt}/{@code day_completion} repositories rather than reaching for JPA.
 *
 * <p>Unknown family strings are ignored on read rather than throwing: a family renamed or removed
 * from the enum is a dead row, not a reason to fail every warm-up. The enum is the source of truth.
 */
@Repository
public class RoleRepository {

    private final JdbcClient jdbc;

    public RoleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The families this user has turned on; empty when the user has not chosen a role yet. */
    public Set<Family> activeFamilies(UUID userId) {
        Set<Family> families = EnumSet.noneOf(Family.class);
        jdbc.sql("SELECT family FROM active_family WHERE user_id = :userId")
                .param("userId", userId)
                .query(String.class)
                .list()
                .forEach(name -> parse(name).ifPresent(families::add));
        return families;
    }

    /**
     * Replaces this user's active families with exactly the given set, atomically. Selecting a role
     * is a wholesale swap (a preset expands to a set, it does not toggle individual rows), so the
     * old rows are cleared and the new ones inserted in one transaction - never a half-applied set.
     */
    @Transactional
    public void replaceActiveFamilies(UUID userId, Set<Family> families) {
        jdbc.sql("DELETE FROM active_family WHERE user_id = :userId")
                .param("userId", userId)
                .update();
        for (Family family : families) {
            jdbc.sql("INSERT INTO active_family (user_id, family) VALUES (:userId, :family)")
                    .param("userId", userId)
                    .param("family", family.name())
                    .update();
        }
    }

    private static java.util.Optional<Family> parse(String name) {
        try {
            return java.util.Optional.of(Family.valueOf(name));
        } catch (IllegalArgumentException dead) {
            return java.util.Optional.empty();
        }
    }
}
