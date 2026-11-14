package dev.flowhook.deliveries;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "flowhook.delivery.local-receiver-enabled=true")
@AutoConfigureMockMvc
@Testcontainers
class DeliveryApiTest {
    static final AtomicReference<HttpHandler> handler = new AtomicReference<>();
    static final AtomicInteger hits = new AtomicInteger();
    static final java.util.concurrent.ExecutorService httpThreads = Executors.newVirtualThreadPerTaskExecutor();
    static final HttpServer receiver = receiver();
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-bookworm");

    private static HttpServer receiver() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(httpThreads);
            server.createContext("/", exchange -> {
                hits.incrementAndGet();
                try { handler.get().handle(exchange); } finally { exchange.close(); }
            });
            server.start();
            return server;
        } catch (IOException exception) { throw new ExceptionInInitializerError(exception); }
    }

    @AfterAll static void closeReceiver() { receiver.stop(0); httpThreads.shutdownNow(); }

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("flowhook.delivery.local-receiver-port", () -> receiver.getAddress().getPort());
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired DeliveryService service;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

    @BeforeEach void reset() {
        jdbc.sql("TRUNCATE public.events, public.endpoints RESTART IDENTITY CASCADE").update();
        hits.set(0);
        handler.set(exchange -> reply(exchange, 200, "ok".getBytes(StandardCharsets.UTF_8)));
    }

    private String target() { return "http://127.0.0.1:" + receiver.getAddress().getPort() + "/webhooks"; }
    private static void reply(HttpExchange exchange, int code, byte[] body) throws IOException {
        exchange.sendResponseHeaders(code, body.length);
        exchange.getResponseBody().write(body);
    }
    private String send(long id) { return "/api/projects/1/deliveries/" + id + "/send"; }

    private long delivery(String url) {
        long endpointId = jdbc.sql("INSERT INTO public.endpoints (project_id,name,url,event_type) VALUES (1,'demo',:url,'order.created') RETURNING id")
                .param("url", url).query(Long.class).single();
        long eventId = jdbc.sql("INSERT INTO public.events (project_id,event_id,type,data) VALUES (1,'test','order.created','{}') RETURNING id")
                .query(Long.class).single();
        return jdbc.sql("INSERT INTO public.deliveries (event_id,endpoint_id,target_url,payload) VALUES (:event,:endpoint,:url,'{\"eventId\":\"test\",\"type\":\"order.created\",\"data\":{}}') RETURNING id")
                .param("event", eventId).param("endpoint", endpointId).param("url", url).query(Long.class).single();
    }

    @Test void sendsFrozenPayloadAfterClaimCommitAndRecordsSuccess() throws Exception {
        long id = delivery(target());
        var received = new AtomicReference<String>();
        handler.set(exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            assertEquals(String.valueOf(id), exchange.getRequestHeaders().getFirst("X-FlowHook-Delivery-Id"));
            assertEquals("application/json", exchange.getRequestHeaders().getFirst("Content-Type"));
            assertEquals("PROCESSING", jdbc.sql("SELECT status FROM public.deliveries WHERE id=:id").param("id", id).query(String.class).single());
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(exchange, 200, "ok".getBytes(StandardCharsets.UTF_8));
        });
        jdbc.sql("UPDATE public.endpoints SET url='https://changed.example.com'").update();
        mvc.perform(post(send(id))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.httpStatus").value(200)).andExpect(jsonPath("$.outcome").value("SUCCESS"));
        assertTrue(received.get().contains("order.created"));
        assertEquals(1, hits.get());
        assertEquals(1L, jdbc.sql("SELECT count(*) FROM public.delivery_attempts WHERE outcome='SUCCESS' AND finished_at IS NOT NULL").query(Long.class).single());
        mvc.perform(post(send(id))).andExpect(status().isConflict());
        assertEquals(1, hits.get());
    }

    @Test void listsJobsAndAcceptsLocalUrlThroughRealApi() throws Exception {
        mvc.perform(post("/api/projects/1/endpoints").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"demo\",\"url\":\"" + target() + "\",\"eventType\":\"order.created\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/v1/events").contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventId\":\"api\",\"type\":\"order.created\",\"data\":{}}"))
                .andExpect(status().isAccepted());
        mvc.perform(get("/api/projects/1/deliveries")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1)).andExpect(jsonPath("$[0].status").value("PENDING"));
        mvc.perform(post(send(1))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"));
    }

    @Test void recordsHttpFailureWithoutAutomaticRetry() throws Exception {
        handler.set(exchange -> reply(exchange, 500, "failure".getBytes(StandardCharsets.UTF_8)));
        mvc.perform(post(send(delivery(target() + "?mode=failure")))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEAD")).andExpect(jsonPath("$.httpStatus").value(500))
                .andExpect(jsonPath("$.outcome").value("HTTP_ERROR"));
        assertEquals(1, hits.get());
    }

    @Test void refusesDisabledEndpointAndOtherProject() throws Exception {
        long id = delivery(target());
        jdbc.sql("UPDATE public.endpoints SET active=false").update();
        mvc.perform(post(send(id))).andExpect(status().isConflict());
        mvc.perform(post("/api/projects/2/deliveries/" + id + "/send")).andExpect(status().isNotFound());
        mvc.perform(post(send(999))).andExpect(status().isNotFound());
        assertEquals(0, hits.get());
        assertEquals(0L, jdbc.sql("SELECT count(*) FROM public.delivery_attempts").query(Long.class).single());
    }

    @Test void blockedUrlsNeverReachReceiver() throws Exception {
        for (String bad : new String[]{"http://localhost:" + receiver.getAddress().getPort() + "/webhooks", target() + "/other", target() + "?redirect=http://example.com", "https://example.com"}) {
            long id = delivery(bad);
            mvc.perform(post(send(id))).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("URL_BLOCKED"));
            jdbc.sql("TRUNCATE public.events, public.endpoints RESTART IDENTITY CASCADE").update();
        }
        assertEquals(0, hits.get());
    }

    @Test void redirectIsNotFollowed() throws Exception {
        handler.set(exchange -> {
            exchange.getResponseHeaders().set("Location", target());
            exchange.sendResponseHeaders(302, -1);
        });
        mvc.perform(post(send(delivery(target())))).andExpect(status().isOk())
                .andExpect(jsonPath("$.httpStatus").value(302)).andExpect(jsonPath("$.outcome").value("HTTP_ERROR"));
        assertEquals(1, hits.get());
    }

    @Test void storesOnly4096ResponseBytesIncludingBinaryData() throws Exception {
        handler.set(exchange -> reply(exchange, 200, new byte[10000]));
        mvc.perform(post(send(delivery(target())))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
        assertEquals(4096, jdbc.sql("SELECT octet_length(response_body) FROM public.delivery_attempts").query(Integer.class).single());
        assertTrue(jdbc.sql("SELECT response_truncated FROM public.delivery_attempts").query(Boolean.class).single());
    }

    @Test void timesOutDelayedHeaders() throws Exception {
        handler.set(exchange -> {
            try { Thread.sleep(8000); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            reply(exchange, 200, new byte[]{1});
        });
        long start = System.nanoTime();
        mvc.perform(post(send(delivery(target())))).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("TIMEOUT")).andExpect(jsonPath("$.status").value("DEAD"));
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 8000);
    }

    @Test void wholeTimeoutAlsoBoundsStalledResponseBody() throws Exception {
        handler.set(exchange -> {
            exchange.sendResponseHeaders(200, 10);
            exchange.getResponseBody().write(1);
            exchange.getResponseBody().flush();
            try { Thread.sleep(15000); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
        });
        long start = System.nanoTime();
        mvc.perform(post(send(delivery(target())))).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("TIMEOUT"));
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 14000);
    }

    @Test void sameDeliveryConcurrentRequestsCauseOneHttpCall() throws Exception {
        long id = delivery(target());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        handler.set(exchange -> {
            entered.countDown();
            try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            reply(exchange, 200, new byte[]{1});
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> mvc.perform(post(send(id))).andReturn().getResponse().getStatus());
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                mvc.perform(post(send(id))).andExpect(status().isConflict());
            } finally { release.countDown(); }
            assertEquals(200, first.get());
        }
        assertEquals(1, hits.get());
    }

    @Test void storageFailureAfterHttpKeepsUnknownAttemptAndProcessingJob() throws Exception {
        long id = delivery(target());
        jdbc.sql("ALTER TABLE public.delivery_attempts ADD CONSTRAINT test_reject_result CHECK (finished_at IS NULL) NOT VALID").update();
        try {
            mvc.perform(post(send(id))).andExpect(status().isServiceUnavailable());
            assertEquals("PROCESSING", jdbc.sql("SELECT status FROM public.deliveries").query(String.class).single());
            assertEquals("UNKNOWN", jdbc.sql("SELECT outcome FROM public.delivery_attempts").query(String.class).single());
            assertEquals(1, hits.get());
        } finally { jdbc.sql("ALTER TABLE public.delivery_attempts DROP CONSTRAINT test_reject_result").update(); }
    }

    @Test void refusesCallerTransactionBeforeAnyClaimOrHttp() {
        long id = delivery(target());
        var transaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,
                () -> transaction.execute(status -> service.sendOnce(1, id)));
        assertEquals(0, hits.get());
        assertEquals(0L, jdbc.sql("SELECT count(*) FROM public.delivery_attempts").query(Long.class).single());
    }

    @Test void recordsAbruptConnectionFailureWithoutRetry() throws Exception {
        handler.set(HttpExchange::close);
        mvc.perform(post(send(delivery(target())))).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("NETWORK_ERROR")).andExpect(jsonPath("$.status").value("DEAD"));
        assertEquals(1, hits.get());
    }
}
