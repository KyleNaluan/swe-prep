package com.sweprep.backend.sql;

/**
 * Small SQL-text helpers shared by {@link SqlFixtureProvisioner} and {@link
 * PostgresSqlRunner}. Both build DDL/DCL statements by string concatenation rather than a
 * bind parameter, since JDBC parameters cannot stand in for identifiers (a schema, role or
 * database name) - safe here only because every value that flows through {@link
 * #quoteIdent} is either fixed config or a fixture name {@code ExerciseParser} has already
 * restricted to {@code [a-z][a-z0-9_]*} at load time (issue #25), never end-user input.
 */
final class SqlIdentifiers {

    private SqlIdentifiers() {}

    /** Double-quotes a Postgres identifier, escaping any embedded quote. */
    static String quoteIdent(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    /** A JDBC URL with its trailing database name replaced by {@code database}. */
    static String withDatabase(String jdbcUrl, String database) {
        int lastSlash = jdbcUrl.lastIndexOf('/');
        int queryStart = jdbcUrl.indexOf('?', lastSlash);
        String suffix = queryStart >= 0 ? jdbcUrl.substring(queryStart) : "";
        return jdbcUrl.substring(0, lastSlash + 1) + database + suffix;
    }
}
