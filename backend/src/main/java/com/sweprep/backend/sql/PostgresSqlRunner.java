package com.sweprep.backend.sql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/**
 * The SQL domain's {@link SqlRunner}: runs one submitted query against the fixture
 * database, as the dedicated read-only role, inside a transaction that is always rolled
 * back - never committed, whether the query succeeds or fails (issue #25, decision issue
 * #10). This is the concrete demonstration of the acceptance criteria: the read-only role
 * has no write grant on any fixture schema, and the connection's own transaction is marked
 * {@code READ ONLY} on top of that (Postgres enforces both independently), so a submission
 * that attempts to modify or drop fixture data is refused outright - reported back as a
 * {@link SqlExecutionResult.Outcome#QUERY_ERROR} naming the database's own refusal, never
 * silently allowed and then undone.
 */
@Component
public class PostgresSqlRunner implements SqlRunner {

    /** Postgres SQLSTATE for a statement cancelled by {@link Statement#setQueryTimeout}. */
    private static final String QUERY_CANCELED = "57014";

    private final SqlFixtureProvisioner provisioner;
    private final SqlFixtureProperties props;
    private final String fixtureJdbcUrl;
    private final ObjectMapper mapper;

    public PostgresSqlRunner(
            SqlFixtureProvisioner provisioner,
            SqlFixtureProperties props,
            DataSource appDataSource,
            ObjectMapper mapper) {
        this.provisioner = provisioner;
        this.props = props;
        String mainJdbcUrl = AdminConnection.of(appDataSource).jdbcUrl();
        this.fixtureJdbcUrl = SqlIdentifiers.withDatabase(mainJdbcUrl, props.fixtureDatabase());
        this.mapper = mapper;
    }

    @Override
    public SqlExecutionResult execute(SqlExecutionRequest request) {
        provisioner.ensureLoaded(request.fixture());
        try (Connection conn = DriverManager.getConnection(
                fixtureJdbcUrl, props.readerUsername(), props.readerPassword())) {
            // Belt and suspenders (issue #25): the reader role already holds no write grant
            // on any fixture schema, and marking the transaction itself READ ONLY makes
            // Postgres refuse any write independently of grants, at the transaction level.
            // Either alone would satisfy "refused, not merely undone"; both together mean a
            // misconfigured grant is not the only thing standing in a DROP TABLE's way.
            conn.setAutoCommit(false);
            conn.setReadOnly(true);
            try {
                restrictSearchPath(conn, request.fixture());
                return runQuery(conn, request);
            } finally {
                // Always rolled back, success or failure: nothing a submission does is ever
                // persisted, which is the other half of issue #25's transaction guarantee.
                conn.rollback();
            }
        } catch (SQLException e) {
            throw new SqlFixtureException("Could not run the submitted query", e);
        }
    }

    private void restrictSearchPath(Connection conn, String fixture) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("SET search_path TO " + SqlIdentifiers.quoteIdent(fixture));
        }
    }

    private SqlExecutionResult runQuery(Connection conn, SqlExecutionRequest request) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.setQueryTimeout((int) Math.max(1, request.timeout().toSeconds()));
            boolean hasResultSet;
            try {
                hasResultSet = st.execute(request.query() == null ? "" : request.query());
            } catch (SQLException e) {
                return isTimeout(e)
                        ? SqlExecutionResult.timeout("Execution timed out after "
                                + request.timeout().toSeconds() + "s (possible runaway query)")
                        : SqlExecutionResult.queryError(message(e));
            }
            if (!hasResultSet) {
                return SqlExecutionResult.queryError(
                        "The submission must be a single query that returns a result set (e.g. a "
                                + "SELECT) - it produced no rows to grade");
            }
            try (ResultSet rs = st.getResultSet()) {
                return SqlExecutionResult.completed(toRows(rs));
            }
        }
    }

    /**
     * Reads the result set back as a JSON array of rows, each row itself a JSON array of
     * column values in position - column names never travel past this point (issue #25).
     * Values are converted by declared column type, not left to Jackson's default {@code
     * valueToTree} on the raw JDBC object, so a NUMERIC/BIGINT/INTEGER all become a JSON
     * number the shared {@code JsonEquality} primitive compares by magnitude, and a SQL
     * {@code NULL} always becomes {@link NullNode} rather than a Java {@code null} - the
     * numeric-type and NULL normalisation the ticket calls out as required.
     */
    private JsonNode toRows(ResultSet rs) throws SQLException {
        ArrayNode rows = mapper.createArrayNode();
        ResultSetMetaData meta = rs.getMetaData();
        int columns = meta.getColumnCount();
        while (rs.next()) {
            ArrayNode row = mapper.createArrayNode();
            for (int i = 1; i <= columns; i++) {
                row.add(columnValue(rs, meta, i));
            }
            rows.add(row);
        }
        return rows;
    }

    private JsonNode columnValue(ResultSet rs, ResultSetMetaData meta, int index) throws SQLException {
        JsonNodeFactory nodes = mapper.getNodeFactory();
        int type = meta.getColumnType(index);
        return switch (type) {
            case Types.BIGINT, Types.INTEGER, Types.SMALLINT, Types.TINYINT -> {
                long value = rs.getLong(index);
                yield rs.wasNull() ? NullNode.instance : nodes.numberNode(value);
            }
            case Types.NUMERIC, Types.DECIMAL -> {
                BigDecimal value = rs.getBigDecimal(index);
                yield value == null ? NullNode.instance : nodes.numberNode(value);
            }
            case Types.REAL, Types.FLOAT, Types.DOUBLE -> {
                double value = rs.getDouble(index);
                yield rs.wasNull() ? NullNode.instance : nodes.numberNode(value);
            }
            case Types.BOOLEAN, Types.BIT -> {
                boolean value = rs.getBoolean(index);
                yield rs.wasNull() ? NullNode.instance : nodes.booleanNode(value);
            }
            case Types.DATE -> {
                java.sql.Date value = rs.getDate(index);
                yield value == null ? NullNode.instance : nodes.textNode(value.toLocalDate().toString());
            }
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> {
                java.sql.Timestamp value = rs.getTimestamp(index);
                yield value == null
                        ? NullNode.instance
                        : nodes.textNode(value.toLocalDateTime().toString());
            }
            default -> {
                String value = rs.getString(index);
                yield value == null ? NullNode.instance : nodes.textNode(value);
            }
        };
    }

    private static boolean isTimeout(SQLException e) {
        return QUERY_CANCELED.equals(e.getSQLState());
    }

    private static String message(SQLException e) {
        String message = e.getMessage();
        return message == null ? e.getClass().getSimpleName() : message.strip();
    }
}
