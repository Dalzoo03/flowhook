package dev.flowhook.migrations;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class FlywayMigrationTest {
    @Container
    final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-bookworm");

    private Flyway flyway() {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .defaultSchema("public").baselineVersion("1").baselineOnMigrate(false)
                .cleanDisabled(true).load();
    }

    @Test
    void createsEmptyDatabaseOnceAndValidatesHistory() throws Exception {
        Flyway flyway = flyway();
        assertEquals(1, flyway.migrate().migrationsExecuted);
        assertEquals("1", flyway.info().current().getVersion().getVersion());
        assertEquals("SQL", flyway.info().current().getType().name());
        assertEquals(0, flyway.migrate().migrationsExecuted);
        flyway.validate();
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT count(*) FROM public.endpoints")) {
            assertTrue(rows.next());
            assertEquals(0, rows.getLong(1));
        }
    }

    @Test
    void existingTableRequiresExplicitBaselineAndKeepsData() throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V1__create_endpoints.sql"));
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO public.endpoints (project_id, name, url, event_type) VALUES (1, '기존 데이터', 'https://example.com', 'order.created')");
            }
        }
        Flyway flyway = flyway();
        assertThrows(FlywayException.class, flyway::migrate);
        flyway.baseline();
        assertEquals(0, flyway.migrate().migrationsExecuted);
        assertEquals("BASELINE", flyway.info().current().getType().name());
        flyway.validate();
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT id, name FROM public.endpoints")) {
            assertTrue(rows.next());
            assertEquals(1, rows.getLong("id"));
            assertEquals("기존 데이터", rows.getString("name"));
            assertFalse(rows.next());
        }
    }
}
