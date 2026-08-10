package com.sweprep.backend.sql;

import com.sweprep.backend.content.SqlFixtureCatalog;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/**
 * Idempotently prepares the SQL domain's infrastructure (issue #25, decision issue #10):
 * the fixture database and its dedicated read-only role, then each named fixture's schema
 * and seed data, loaded from content on first use. Every attempt calls {@link
 * #ensureLoaded} before running a query; a fixture already ensured this process, or already
 * seeded from an earlier boot, is a cheap no-op.
 *
 * <p>Provisioning runs over the app's own datasource - the same Postgres instance, using
 * its already-superuser credentials (the docker-compose Postgres user, or Testcontainers'
 * container user, both superuser in their own instance) - because {@code CREATE DATABASE}
 * and {@code CREATE ROLE} are cluster-wide operations any existing connection to that
 * instance can issue; there is no need to connect to a separate administrative database
 * first. Once the fixture database exists, a second, plain JDBC connection to it (still
 * admin-privileged, never the reader role) creates each fixture's schema and grants the
 * reader role {@code SELECT} there - the query-time connection in {@link PostgresSqlRunner}
 * is the one that actually runs as that restricted role.
 */
@Component
public class SqlFixtureProvisioner {

    private final DataSource appDataSource;
    private final SqlFixtureCatalog fixtures;
    private final SqlFixtureProperties props;
    private final String fixtureJdbcUrl;
    private final String adminUsername;
    private final String adminPassword;

    private final Object lock = new Object();
    private volatile boolean infraReady = false;
    private final Set<String> loadedFixtures = ConcurrentHashMap.newKeySet();

    public SqlFixtureProvisioner(DataSource appDataSource, SqlFixtureCatalog fixtures, SqlFixtureProperties props) {
        this.appDataSource = appDataSource;
        this.fixtures = fixtures;
        this.props = props;
        AdminConnection.Details admin = AdminConnection.of(appDataSource);
        this.fixtureJdbcUrl = SqlIdentifiers.withDatabase(admin.jdbcUrl(), props.fixtureDatabase());
        this.adminUsername = admin.username();
        this.adminPassword = admin.password();
    }

    /**
     * Ensures the named fixture's schema and seed data exist in the fixture database and
     * are readable by the reader role, loading them from {@link SqlFixtureCatalog} the
     * first time this process needs them. Safe to call before every attempt.
     */
    public void ensureLoaded(String fixture) {
        ensureInfra();
        if (loadedFixtures.contains(fixture)) {
            return;
        }
        synchronized (lock) {
            if (loadedFixtures.contains(fixture)) {
                return;
            }
            try (Connection conn = DriverManager.getConnection(fixtureJdbcUrl, adminUsername, adminPassword)) {
                conn.setAutoCommit(true);
                loadFixtureIfAbsent(conn, fixture);
                grantReaderAccess(conn, fixture);
            } catch (SQLException e) {
                throw new SqlFixtureException("Could not provision SQL fixture '" + fixture + "'", e);
            }
            loadedFixtures.add(fixture);
        }
    }

    private void ensureInfra() {
        if (infraReady) {
            return;
        }
        synchronized (lock) {
            if (infraReady) {
                return;
            }
            try (Connection conn = appDataSource.getConnection()) {
                boolean autoCommit = conn.getAutoCommit();
                conn.setAutoCommit(true);
                try (Statement st = conn.createStatement()) {
                    ensureReaderRole(st);
                    ensureFixtureDatabase(st);
                }
                conn.setAutoCommit(autoCommit);
            } catch (SQLException e) {
                throw new SqlFixtureException("Could not provision the SQL fixture database or role", e);
            }
            infraReady = true;
        }
    }

    private void ensureReaderRole(Statement st) throws SQLException {
        if (exists(st, "SELECT 1 FROM pg_roles WHERE rolname = '" + props.readerUsername() + "'")) {
            return;
        }
        // NOSUPERUSER/NOCREATEDB/NOCREATEROLE/NOINHERIT: the role's only privilege is
        // whatever SELECT grant grantReaderAccess gives it per fixture schema - the "as a
        // read-only role" half of issue #25's acceptance criteria.
        st.execute("CREATE ROLE " + SqlIdentifiers.quoteIdent(props.readerUsername())
                + " LOGIN PASSWORD '" + props.readerPassword().replace("'", "''") + "'"
                + " NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT");
    }

    private void ensureFixtureDatabase(Statement st) throws SQLException {
        if (exists(st, "SELECT 1 FROM pg_database WHERE datname = '" + props.fixtureDatabase() + "'")) {
            return;
        }
        st.execute("CREATE DATABASE " + SqlIdentifiers.quoteIdent(props.fixtureDatabase())
                + " OWNER " + SqlIdentifiers.quoteIdent(adminUsername));
    }

    private void loadFixtureIfAbsent(Connection conn, String fixture) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE SCHEMA IF NOT EXISTS " + SqlIdentifiers.quoteIdent(fixture));
            if (exists(st, "SELECT 1 FROM information_schema.tables WHERE table_schema = '"
                    + fixture + "' LIMIT 1")) {
                // Already seeded by an earlier boot against this same fixture database -
                // shared schemas persist across restarts rather than being torn down and
                // reloaded on every one (decision issue #10: a schema is learned once).
                return;
            }
            st.execute("SET search_path TO " + SqlIdentifiers.quoteIdent(fixture));
            st.execute(fixtures.sql(fixture));
        }
    }

    private void grantReaderAccess(Connection conn, String fixture) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("GRANT USAGE ON SCHEMA " + SqlIdentifiers.quoteIdent(fixture)
                    + " TO " + SqlIdentifiers.quoteIdent(props.readerUsername()));
            st.execute("GRANT SELECT ON ALL TABLES IN SCHEMA " + SqlIdentifiers.quoteIdent(fixture)
                    + " TO " + SqlIdentifiers.quoteIdent(props.readerUsername()));
        }
    }

    private static boolean exists(Statement st, String query) throws SQLException {
        try (ResultSet rs = st.executeQuery(query)) {
            return rs.next();
        }
    }
}
