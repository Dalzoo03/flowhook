import java.io.OutputStream;
import java.io.PrintStream;
import java.time.Duration;
import dev.flowhook.FlowhookApplication;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = FlowhookApplication.class)
@ActiveProfiles("local")
@AutoConfigureMockMvc
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FlowHookReceiverIntegrationTest {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-bookworm");
    static Receiver receiver;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) throws Exception {
        var store = new ReceiverStore(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        store.migrate();
        receiver = Receiver.start(0, 8000, new PrintStream(OutputStream.nullOutputStream()), store, dev.flowhook.signing.SigningTestSupport.SIGNER);
        registry.add("flowhook.delivery.signing-key", () -> dev.flowhook.signing.SigningTestSupport.KEY);
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("flowhook.delivery.local-receiver-port", receiver::port);
    }

    @AfterAll static void close() { if (receiver != null) receiver.close(); }
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @Test void timeoutThenAutomaticRetryReceivesTwiceButAppliesBusinessOnce() throws Exception {
        String url = "http://127.0.0.1:" + receiver.port() + "/webhooks?mode=delay";
        mvc.perform(post("/api/projects/1/endpoints").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"idempotent receiver\",\"url\":\"" + url + "\",\"eventType\":\"demo.idempotent\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/v1/events").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"idempotent-001\",\"type\":\"demo.idempotent\",\"data\":{\"orderId\":7001}}"))
                .andExpect(status().isAccepted());
        long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
        while (!jdbc.sql("SELECT status='SUCCEEDED' FROM public.deliveries").query(Boolean.class).single()) {
            if (System.nanoTime() >= deadline) fail("Automatic retry did not succeed");
            Thread.sleep(50);
        }
        assertEquals(2, jdbc.sql("SELECT attempt_count FROM public.deliveries").query(Integer.class).single());
        assertEquals(java.util.List.of("TIMEOUT", "SUCCESS"), jdbc.sql("SELECT outcome FROM public.delivery_attempts ORDER BY attempt_no").query(String.class).list());
        assertEquals(1L, jdbc.sql("SELECT count(*) FROM demo_receiver.receipts").query(Long.class).single());
        assertEquals(2, jdbc.sql("SELECT receipt_count FROM demo_receiver.receipts").query(Integer.class).single());
        assertEquals(1L, jdbc.sql("SELECT count(*) FROM demo_receiver.business_effects").query(Long.class).single());
        assertTrue(jdbc.sql("SELECT r.delivery_id=d.id::text FROM demo_receiver.receipts r CROSS JOIN public.deliveries d").query(Boolean.class).single());
    }
}
