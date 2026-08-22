package com.shortener.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class Phase3OwnershipMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15-alpine");

    @Test
    void migratesExistingPhase2DataToLegacyOwnershipWithoutLosingDependents() throws SQLException {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("2"))
                .load()
                .migrate();

        try (Connection connection = openConnection()) {
            seedPhase2Data(connection);
            assertEquals(2, queryLong(connection, "SELECT COUNT(*) FROM url_mappings"));
            assertEquals(2, queryLong(connection, "SELECT COUNT(*) FROM url_stats"));
            assertEquals(2, queryLong(connection, "SELECT COUNT(*) FROM url_analytics"));
            assertEquals(2, queryLong(connection, "SELECT COUNT(*) FROM url_unique_visitors"));
        }

        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = openConnection()) {
            assertEquals(2, queryLong(connection, "SELECT COUNT(*) FROM url_mappings"));
            assertEquals(2, queryLong(connection,
                    "SELECT COUNT(*) FROM url_mappings WHERE owner_client_id = 'legacy-system'"));
            assertEquals(2, queryLong(connection, "SELECT COUNT(*) FROM url_stats"));
            assertEquals(2, queryLong(connection, "SELECT COUNT(*) FROM url_analytics"));
            assertEquals(2, queryLong(connection, "SELECT COUNT(*) FROM url_unique_visitors"));

            assertFalse(queryBoolean(connection,
                    "SELECT active FROM api_clients WHERE client_id = 'legacy-system'"));
            assertEquals("ADMIN", queryString(connection,
                    "SELECT authorities FROM api_clients WHERE client_id = 'legacy-system'"));

            assertTrue(queryBoolean(connection,
                    "SELECT is_active FROM url_mappings WHERE id = 101"));
            assertFalse(queryBoolean(connection,
                    "SELECT is_active FROM url_mappings WHERE id = 102"));
            assertEquals(7, queryLong(connection,
                    "SELECT click_count FROM url_stats WHERE url_id = 101"));
            assertEquals(2, queryLong(connection,
                    "SELECT unique_visitors FROM url_stats WHERE url_id = 101"));
            assertEquals("Mumbai", queryString(connection,
                    "SELECT city FROM url_analytics WHERE id = 301"));
            assertEquals("visitor-a", queryString(connection,
                    "SELECT visitor_hash FROM url_unique_visitors WHERE url_id = 101"));

            assertConstraintViolation(
                    connection,
                    "INSERT INTO url_mappings(short_code, original_url) "
                            + "VALUES ('missing1', 'https://example.com/missing-owner')",
                    "23502"
            );
            assertConstraintViolation(
                    connection,
                    "INSERT INTO url_mappings(short_code, original_url, owner_client_id) "
                            + "VALUES ('orphan1', 'https://example.com/orphan-owner', 'unknown-client')",
                    "23503"
            );
            assertEquals(2, queryLong(connection, "SELECT COUNT(*) FROM url_mappings"));
        }
    }

    private static Connection openConnection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        );
    }

    private static void seedPhase2Data(Connection connection) throws SQLException {
        executeUpdate(connection, """
                INSERT INTO url_mappings(id, short_code, original_url, is_active)
                VALUES
                    (101, 'legacy1', 'https://example.com/legacy-one', TRUE),
                    (102, 'legacy2', 'https://example.com/legacy-two', FALSE)
                """);
        executeUpdate(connection, """
                INSERT INTO url_stats(
                    id, url_id, click_count, unique_visitors,
                    mobile_clicks, desktop_clicks, tablet_clicks
                )
                VALUES
                    (201, 101, 7, 2, 3, 3, 1),
                    (202, 102, 4, 1, 1, 3, 0)
                """);
        executeUpdate(connection, """
                INSERT INTO url_analytics(
                    id, url_id, device_type, referrer, ip_address, user_agent,
                    city, country, continent, visitor_hash
                )
                VALUES
                    (301, 101, 'Mobile', 'Direct', '198.51.100.10', 'agent-a',
                     'Mumbai', 'India', 'Asia', 'visitor-a'),
                    (302, 102, 'Desktop', 'https://search.example', '198.51.100.11', 'agent-b',
                     'Pune', 'India', 'Asia', 'visitor-b')
                """);
        executeUpdate(connection, """
                INSERT INTO url_unique_visitors(url_id, visitor_hash)
                VALUES (101, 'visitor-a'), (102, 'visitor-b')
                """);
    }

    private static void assertConstraintViolation(
            Connection connection,
            String sql,
            String expectedSqlState
    ) {
        SQLException exception = assertThrows(
                SQLException.class,
                () -> executeUpdate(connection, sql)
        );
        assertEquals(expectedSqlState, exception.getSQLState());
    }

    private static void executeUpdate(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static long queryLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getLong(1);
        }
    }

    private static boolean queryBoolean(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getBoolean(1);
        }
    }

    private static String queryString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }
}
