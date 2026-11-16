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
        assertEquals(5, flyway.migrate().migrationsExecuted);
        assertEquals("5", flyway.info().current().getVersion().getVersion());
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
        assertEquals(4, flyway.migrate().migrationsExecuted);
        assertEquals("5", flyway.info().current().getVersion().getVersion());
        assertTrue(java.util.Arrays.stream(flyway.info().all())
                .anyMatch(info -> info.getType().name().equals("BASELINE")
                        && info.getVersion().getVersion().equals("1")));
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

    @Test
    void upgradesV3PreservingTerminalAndUnknownHistory() throws Exception {
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .target("3").load().migrate();
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO public.endpoints (project_id,name,url,event_type) VALUES (1,'demo','https://example.com','x')");
            statement.executeUpdate("INSERT INTO public.events (project_id,event_id,type,data) SELECT 1, 'old-'||g, 'x', '{}'::jsonb FROM generate_series(1,4) g");
            statement.executeUpdate("INSERT INTO public.deliveries (event_id,endpoint_id,target_url,payload,status) SELECT id,1,'https://example.com','{}'::jsonb, CASE id WHEN 1 THEN 'DEAD' WHEN 2 THEN 'SUCCEEDED' WHEN 3 THEN 'PROCESSING' ELSE 'PENDING' END FROM public.events");
            statement.executeUpdate("INSERT INTO public.delivery_attempts (delivery_id,outcome,http_status,finished_at) SELECT id, CASE id WHEN 1 THEN 'HTTP_ERROR' WHEN 2 THEN 'SUCCESS' ELSE 'UNKNOWN' END, CASE id WHEN 1 THEN 500 WHEN 2 THEN 200 END, CASE WHEN id<>3 THEN clock_timestamp() END FROM public.deliveries WHERE id<=3");
        }
        assertEquals(2, flyway().migrate().migrationsExecuted);
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT status,attempt_count,next_attempt_at FROM public.deliveries ORDER BY id")) {
                for (String expected : new String[]{"DEAD","SUCCEEDED","PROCESSING","PENDING"}) {
                    assertTrue(rows.next());
                    assertEquals(expected, rows.getString("status"));
                    assertEquals(expected.equals("PENDING") ? 0 : 1, rows.getInt("attempt_count"));
                    assertNull(rows.getObject("next_attempt_at"));
                }
            }
            try (var rows = statement.executeQuery("SELECT count(*) FROM public.delivery_attempts WHERE attempt_no=1")) {
                assertTrue(rows.next()); assertEquals(3, rows.getInt(1));
            }
            statement.executeUpdate("INSERT INTO public.delivery_attempts (delivery_id,attempt_no) VALUES (1,2)");
            assertThrows(java.sql.SQLException.class,
                    () -> statement.executeUpdate("INSERT INTO public.delivery_attempts (delivery_id,attempt_no) VALUES (1,2)"));
        }
    }
    @Test void upgradesV4WithGraceLeaseAndPreservesCountsAndUnknownHistory() throws Exception {
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .target("4").load().migrate();
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO public.endpoints(project_id,name,url,event_type) VALUES (1,'old','http://127.0.0.1:9090/webhooks','x')");
            statement.executeUpdate("INSERT INTO public.events(project_id,event_id,type,data) SELECT 1,'v4-'||g,'x','{}'::jsonb FROM generate_series(1,3) g");
            statement.executeUpdate("INSERT INTO public.deliveries(event_id,endpoint_id,target_url,payload,status,attempt_count,next_attempt_at) SELECT id,1,'http://127.0.0.1:9090/webhooks','{}'::jsonb,CASE id WHEN 1 THEN 'PROCESSING' WHEN 2 THEN 'SUCCEEDED' ELSE 'RETRY_WAIT' END,1,CASE WHEN id=3 THEN TIMESTAMPTZ '2030-01-01 00:00:00+00' END FROM public.events");
            statement.executeUpdate("INSERT INTO public.delivery_attempts(delivery_id,attempt_no,outcome,finished_at) SELECT id,1,CASE id WHEN 1 THEN 'UNKNOWN' WHEN 2 THEN 'SUCCESS' ELSE 'HTTP_ERROR' END,CASE WHEN id<>1 THEN clock_timestamp() END FROM public.deliveries");
        }
        assertEquals(1, flyway().migrate().migrationsExecuted);
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT status,attempt_count,lease_token,lease_expires_at > clock_timestamp() AS future,lease_expires_at <= clock_timestamp()+interval '31 seconds' AS bounded,next_attempt_at FROM public.deliveries ORDER BY id")) {
                assertTrue(rows.next()); assertEquals("PROCESSING", rows.getString("status"));
                assertEquals(1, rows.getInt("attempt_count")); assertNotNull(rows.getObject("lease_token"));
                assertTrue(rows.getBoolean("future")); assertTrue(rows.getBoolean("bounded"));
                assertTrue(rows.next()); assertEquals("SUCCEEDED", rows.getString("status")); assertNull(rows.getObject("lease_token"));
                assertTrue(rows.next()); assertEquals("RETRY_WAIT", rows.getString("status")); assertEquals(1, rows.getInt("attempt_count"));
                assertNull(rows.getObject("lease_token")); assertEquals(java.time.OffsetDateTime.parse("2030-01-01T00:00:00Z"), rows.getObject("next_attempt_at", java.time.OffsetDateTime.class));
            }
            try (var rows = statement.executeQuery("SELECT outcome,finished_at,recovery_reason FROM public.delivery_attempts WHERE delivery_id=1")) {
                assertTrue(rows.next()); assertEquals("UNKNOWN", rows.getString("outcome"));
                assertNull(rows.getObject("finished_at")); assertNull(rows.getString("recovery_reason"));
            }
        }
    }

}
