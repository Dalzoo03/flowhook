package dev.flowhook.deliveries;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DeliveryWorkerTest {
    static final AtomicInteger hits = new AtomicInteger();
    static final AtomicInteger failuresLeft = new AtomicInteger();
    static final AtomicReference<CountDownLatch> blockedSend = new AtomicReference<>();
    static final AtomicReference<CountDownLatch> entered = new AtomicReference<>(new CountDownLatch(1));
    static final HttpServer receiver = receiver();
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-bookworm");

    private static HttpServer receiver() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/webhooks", exchange -> {
                try {
                    exchange.getRequestBody().readAllBytes();
                    hits.incrementAndGet();
                    var gate = blockedSend.get();
                    if ("mode=delay".equals(exchange.getRequestURI().getRawQuery()) && gate != null) {
                        entered.get().countDown();
                        try { gate.await(15, TimeUnit.SECONDS); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                    }
                    int code = failuresLeft.getAndUpdate(n -> Math.max(0, n - 1)) > 0 ? 500 : 200;
                    exchange.sendResponseHeaders(code, -1);
                } finally { exchange.close(); }
            });
            server.start();
            return server;
        } catch (IOException exception) { throw new ExceptionInInitializerError(exception); }
    }

    @AfterAll static void stop() { receiver.stop(0); }

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("flowhook.delivery.signing-key", () -> dev.flowhook.signing.SigningTestSupport.KEY);
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("flowhook.delivery.local-receiver-port", () -> receiver.getAddress().getPort());
    }

    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach void reset() {
        jdbc.sql("TRUNCATE public.events, public.endpoints RESTART IDENTITY CASCADE").update();
        hits.set(0);
        failuresLeft.set(0);
        blockedSend.set(null);
        entered.set(new CountDownLatch(1));
    }

    private String target() { return "http://127.0.0.1:" + receiver.getAddress().getPort() + "/webhooks"; }

    private long delivery(String url, long project, boolean active) {
        long endpoint = jdbc.sql("INSERT INTO public.endpoints (project_id,name,url,event_type,active) VALUES (:project,'worker',:url,'demo.worker',:active) RETURNING id")
                .param("project", project).param("url", url).param("active", active).query(Long.class).single();
        long event = jdbc.sql("INSERT INTO public.events (project_id,event_id,type,data) VALUES (:project,:key,'demo.worker','{}') RETURNING id")
                .param("project", project).param("key", "event-" + endpoint).query(Long.class).single();
        return jdbc.sql("INSERT INTO public.deliveries(event_id,endpoint_id,target_url,payload) VALUES (:event,:endpoint,:url,'{}') RETURNING id")
                .param("event", event).param("endpoint", endpoint).param("url", url).query(Long.class).single();
    }

    private String deliveryStatus(long id) {
        return jdbc.sql("SELECT status FROM public.deliveries WHERE id=:id").param("id", id).query(String.class).single();
    }

    private int attempts(long id) {
        return jdbc.sql("SELECT attempt_count FROM public.deliveries WHERE id=:id").param("id", id).query(Integer.class).single();
    }

    private void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        await(condition, Duration.ofSeconds(12));
    }

    private void await(java.util.function.BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) fail("Worker did not reach expected persisted state");
            Thread.sleep(25);
        }
    }

    private <T> T seed(java.util.function.Supplier<T> fixture) {
        return new TransactionTemplate(transactionManager).execute(transaction -> fixture.get());
    }

    private void reserve(long id, int count) {
        jdbc.sql("INSERT INTO public.delivery_attempts (delivery_id,attempt_no,finished_at,outcome,http_status) SELECT :id,g,clock_timestamp(),'HTTP_ERROR',500 FROM generate_series(1,:count) g")
                .param("id", id).param("count", count).update();
        jdbc.sql("UPDATE public.deliveries SET status='RETRY_WAIT',attempt_count=:count,next_attempt_at=clock_timestamp()+interval '1 hour' WHERE id=:id")
                .param("id", id).param("count", count).update();
    }

    private void makeDue(long id) {
        jdbc.sql("UPDATE public.deliveries SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE id=:id AND status='RETRY_WAIT'")
                .param("id", id).update();
    }

    @Test void sendsPendingWithoutManualSendApi() throws Exception {
        mvc.perform(post("/api/projects/1/endpoints").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"worker\",\"url\":\"" + target() + "\",\"eventType\":\"demo.worker\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
        mvc.perform(post("/v1/events").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"automatic-1\",\"type\":\"demo.worker\",\"data\":{\"orderId\":1}}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted());
        long id = jdbc.sql("SELECT id FROM public.deliveries").query(Long.class).single();
        await(() -> deliveryStatus(id).equals("SUCCEEDED"));
        assertEquals(1, hits.get());
        assertEquals(1, attempts(id));
        assertEquals("SUCCESS", jdbc.sql("SELECT outcome FROM public.delivery_attempts WHERE delivery_id=:id").param("id", id).query(String.class).single());
    }

    @Test void skipsFuturePausedForeignAndTerminalJobsWithoutStarvingLocalWork() throws Exception {
        long[] ids = seed(() -> {
            for (int i = 0; i < 12; i++) delivery("https://example.com/webhooks", 1, true);
            long future = delivery(target(), 1, true);
            reserve(future, 1);
            long paused = delivery(target(), 1, false);
            long foreign = delivery(target(), 2, true);
            long[] terminals = new long[3];
            String[] states = {"DEAD", "SUCCEEDED", "PROCESSING"};
            for (int i = 0; i < 3; i++) {
                terminals[i] = delivery(target(), 1, true);
                jdbc.sql("UPDATE public.deliveries SET status=:state WHERE id=:id")
                        .param("state", states[i]).param("id", terminals[i]).update();
            }
            long ready = delivery(target(), 1, true);
            return new long[]{future, paused, foreign, terminals[0], terminals[1], terminals[2], ready};
        });
        await(() -> deliveryStatus(ids[6]).equals("SUCCEEDED"));
        assertEquals(1, hits.get());
        assertEquals("RETRY_WAIT", deliveryStatus(ids[0]));
        assertEquals(1, attempts(ids[0]));
        assertEquals("PENDING", deliveryStatus(ids[1]));
        assertEquals("PENDING", deliveryStatus(ids[2]));
        assertEquals("DEAD", deliveryStatus(ids[3]));
        assertEquals("SUCCEEDED", deliveryStatus(ids[4]));
        assertEquals("PROCESSING", deliveryStatus(ids[5]));
        assertEquals(12L, jdbc.sql("SELECT count(*) FROM public.deliveries WHERE target_url='https://example.com/webhooks' AND status='PENDING' AND attempt_count=0").query(Long.class).single());
    }

    @Test void automaticallyRetriesFailureOnlyWhenDueAndPreservesHistory() throws Exception {
        failuresLeft.set(1);
        long id = delivery(target() + "?mode=failure", 1, true);
        await(() -> deliveryStatus(id).equals("RETRY_WAIT"));
        assertEquals(1, attempts(id));
        // 다음 tick에서도 미래 예약을 건너뛰는지 다른 작업의 완료로 확인한다.
        jdbc.sql("UPDATE public.deliveries SET next_attempt_at=clock_timestamp()+interval '1 hour' WHERE id=:id").param("id", id).update();
        long other = delivery(target(), 1, true);
        await(() -> deliveryStatus(other).equals("SUCCEEDED"));
        assertEquals(1, attempts(id));
        makeDue(id);
        await(() -> deliveryStatus(id).equals("SUCCEEDED"));
        assertEquals(2, attempts(id));
        assertEquals(3, hits.get());
        assertEquals(java.util.List.of("HTTP_ERROR", "SUCCESS"), jdbc.sql("SELECT outcome FROM public.delivery_attempts WHERE delivery_id=:id ORDER BY attempt_no").param("id", id).query(String.class).list());
        assertTrue(jdbc.sql("SELECT next_attempt_at IS NULL FROM public.deliveries WHERE id=:id").param("id", id).query(Boolean.class).single());
    }

    @Test void resumesPersistedDueRetryWithoutResettingAttemptCount() throws Exception {
        long id = seed(() -> {
            long value = delivery(target(), 1, true);
            reserve(value, 2);
            makeDue(value);
            return value;
        });
        await(() -> deliveryStatus(id).equals("SUCCEEDED"));
        assertEquals(3, attempts(id));
        assertEquals(1, hits.get());
        assertEquals(java.util.List.of(1, 2, 3), jdbc.sql("SELECT attempt_no FROM public.delivery_attempts WHERE delivery_id=:id ORDER BY attempt_no").param("id", id).query(Integer.class).list());
    }

    @Test void stopsAutomaticallyAfterFifthFailure() throws Exception {
        failuresLeft.set(100);
        long id = delivery(target(), 1, true);
        for (int number = 1; number <= 4; number++) {
            int expected = number;
            await(() -> attempts(id) == expected && deliveryStatus(id).equals("RETRY_WAIT"));
            makeDue(id);
        }
        await(() -> deliveryStatus(id).equals("DEAD"));
        assertEquals(5, attempts(id));
        assertEquals(5, hits.get());
        failuresLeft.set(0);
        long other = delivery(target(), 1, true);
        await(() -> deliveryStatus(other).equals("SUCCEEDED"));
        assertEquals(5, attempts(id));
        assertEquals(6, hits.get());
    }

    @Test void resultStorageFailureKeepsUnknownAndDoesNotStopNextJob() throws Exception {
        long[] ids = seed(() -> {
            long first = delivery(target(), 1, true);
            long second = delivery(target(), 1, true);
            jdbc.sql("ALTER TABLE public.delivery_attempts ADD CONSTRAINT test_worker_failure CHECK (delivery_id <> " + first + " OR finished_at IS NULL) NOT VALID").update();
            return new long[]{first, second};
        });
        try {
            await(() -> deliveryStatus(ids[1]).equals("SUCCEEDED"));
            assertEquals("PROCESSING", deliveryStatus(ids[0]));
            assertEquals(1, attempts(ids[0]));
            assertEquals("UNKNOWN", jdbc.sql("SELECT outcome FROM public.delivery_attempts WHERE delivery_id=:id").param("id", ids[0]).query(String.class).single());
            assertEquals(2, hits.get());
        } finally {
            jdbc.sql("ALTER TABLE public.delivery_attempts DROP CONSTRAINT test_worker_failure").update();
        }
    }
    @Test void schedulerRecoversExpiredUnknownAndAutomaticallyRetries() throws Exception {
        long id = seed(() -> {
            long value = delivery(target(), 1, true);
            jdbc.sql("UPDATE public.deliveries SET status='PROCESSING',attempt_count=1,lease_token=gen_random_uuid(),lease_expires_at=clock_timestamp()-interval '1 second' WHERE id=:id")
                    .param("id", value).update();
            jdbc.sql("INSERT INTO public.delivery_attempts (delivery_id,attempt_no) VALUES (:id,1)").param("id", value).update();
            return value;
        });
        await(() -> deliveryStatus(id).equals("SUCCEEDED"));
        assertEquals(2, attempts(id));
        assertEquals(1, hits.get());
        assertEquals("LEASE_EXPIRED", jdbc.sql("SELECT recovery_reason FROM public.delivery_attempts WHERE delivery_id=:id AND attempt_no=1").param("id", id).query(String.class).single());
    }

    @Test void recoveryRunsWhileSendingLaneIsBlocked() throws Exception {
        var gate = new CountDownLatch(1);
        blockedSend.set(gate);
        long slow = delivery(target() + "?mode=delay", 1, true);
        long[] expired = new long[1];
        try {
            assertTrue(entered.get().await(5, TimeUnit.SECONDS));
            expired[0] = seed(() -> {
                long value = delivery(target(), 1, true);
                jdbc.sql("UPDATE public.deliveries SET status='PROCESSING',attempt_count=1,lease_token=gen_random_uuid(),lease_expires_at=clock_timestamp()-interval '1 second' WHERE id=:id")
                        .param("id", value).update();
                jdbc.sql("INSERT INTO public.delivery_attempts (delivery_id,attempt_no) VALUES (:id,1)").param("id", value).update();
                return value;
            });
            await(() -> deliveryStatus(expired[0]).equals("RETRY_WAIT"), Duration.ofSeconds(3));
            assertEquals("PROCESSING", deliveryStatus(slow));
            assertEquals(1L, gate.getCount());
        } finally { gate.countDown(); }
        await(() -> deliveryStatus(slow).equals("SUCCEEDED"));
        await(() -> deliveryStatus(expired[0]).equals("SUCCEEDED"));
    }

}
