package com.sweprep.backend.sql;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where and as whom submitted SQL queries run (issue #25's decision issue #10: the same
 * Postgres instance, a separate fixture database, a dedicated read-only role).
 *
 * <p>The reader role's credentials are configuration, not a secret worth protecting - the
 * app is single-user and local (issue #1's destination), and the role itself can only
 * {@code SELECT} inside fixture schemas, so leaking them would disclose nothing a solver
 * cannot already read. The defaults are safe for local dev exactly like the docker-compose
 * Postgres password already is.
 *
 * @param fixtureDatabase the database name fixtures live in, alongside the app's own
 *                        database on the same Postgres instance
 * @param readerUsername  the read-only Postgres role every submitted query runs as
 * @param readerPassword  that role's password
 */
@ConfigurationProperties(prefix = "sweprep.sql")
public record SqlFixtureProperties(String fixtureDatabase, String readerUsername, String readerPassword) {

    public SqlFixtureProperties {
        fixtureDatabase = blankToDefault(fixtureDatabase, "sweprep_fixtures");
        readerUsername = blankToDefault(readerUsername, "sweprep_fixture_reader");
        readerPassword = blankToDefault(readerPassword, "sweprep_fixture_reader");
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
