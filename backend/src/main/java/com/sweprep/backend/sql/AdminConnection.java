package com.sweprep.backend.sql;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;

/**
 * Reads the actual JDBC URL/username/password the app's own {@link DataSource} connects
 * with - whatever configured it. Deliberately not read via {@code @Value("${spring
 * .datasource.*}")}: in a Testcontainers test, {@code @ServiceConnection} wires the
 * container's connection details into a {@code JdbcConnectionDetails} bean the
 * autoconfigured datasource consumes directly, never touching the
 * {@code spring.datasource.*} properties themselves - reading those directly would see a
 * stale or absent value instead of the container's real user and port. Hikari (Spring
 * Boot's default pool, used everywhere this app runs) always carries the values it
 * actually connects with as plain fields regardless of how it was configured, so casting
 * to it is the one place that is correct in every environment - docker-compose, a real
 * deployment, or a test container - with no environment-specific branch.
 */
final class AdminConnection {

    private AdminConnection() {}

    record Details(String jdbcUrl, String username, String password) {}

    static Details of(DataSource dataSource) {
        if (!(dataSource instanceof HikariDataSource hikari)) {
            throw new SqlFixtureException(
                    "The SQL domain's fixture provisioner requires a Hikari-backed DataSource, "
                            + "got " + dataSource.getClass().getName());
        }
        return new Details(hikari.getJdbcUrl(), hikari.getUsername(), hikari.getPassword());
    }
}
