import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.*;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class ReceiverIdempotencyTest {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-bookworm");
    static final HttpClient client = HttpClient.newHttpClient();
    ByteArrayOutputStream output;
    PrintStream log;
    static ReceiverStore store;
    Receiver receiver;

    @BeforeAll static void migrate() {
        store = new ReceiverStore(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        store.migrate();
    }

    @BeforeEach void start() throws Exception {
        execute("TRUNCATE demo_receiver.business_effects, demo_receiver.receipts");
        output = new ByteArrayOutputStream();
        log = new PrintStream(output, true, java.nio.charset.StandardCharsets.UTF_8);
        receiver = Receiver.start(0, 1200, log, store, dev.flowhook.signing.SigningTestSupport.SIGNER);
    }
    @AfterEach void close() { receiver.close(); }

    private static void execute(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) { statement.execute(sql); }
    }
    private static long count(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next()); return rows.getLong(1);
        }
    }
    private HttpRequest request(String id, String body, String query) {
        var signed = dev.flowhook.signing.SigningTestSupport.sign(id, body);
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + receiver.port() + "/webhooks" + query))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .header("X-FlowHook-Delivery-Id", id)
                .header("X-FlowHook-Timestamp", signed.timestamp()).header("X-FlowHook-Signature", signed.signature()).POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }
    private HttpResponse<String> send(String id, String body, String query) throws Exception {
        return client.send(request(id, body, query), HttpResponse.BodyHandlers.ofString());
    }
    private void assertOneEffect(int receipts) throws Exception {
        assertEquals(1, count("SELECT count(*) FROM demo_receiver.receipts"));
        assertEquals(receipts, count("SELECT receipt_count FROM demo_receiver.receipts"));
        assertEquals(1, count("SELECT count(*) FROM demo_receiver.business_effects"));
    }

    @Test void unsignedRequestIsRejectedBeforeBusiness() throws Exception {
        var unsigned = HttpRequest.newBuilder(request("unsigned", "{}", "").uri()).header("Content-Type", "application/json")
                .header("X-FlowHook-Delivery-Id", "unsigned").POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        var response = client.send(unsigned, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode());
        assertEquals(0, output.size());
        assertEquals(0, count("SELECT count(*) FROM demo_receiver.receipts"));
        assertEquals(0, count("SELECT count(*) FROM demo_receiver.business_effects"));
    }

    @Test void sameDeliveryReturnsSuccessTwiceButAppliesBusinessOnce() throws Exception {
        var first = send("42", "{\"orderId\":1001}", "");
        var second = send("42", "{\"orderId\":1001}", "");
        assertEquals(200, first.statusCode()); assertTrue(first.body().contains("\"businessApplied\":true"));
        assertEquals(200, second.statusCode()); assertTrue(second.body().contains("\"duplicate\":true"));
        assertTrue(second.body().contains("\"businessApplied\":false"));
        assertOneEffect(2);
    }

    @Test void concurrentDuplicatesApplyBusinessOnlyOnce() throws Exception {
        try (var threads = Executors.newFixedThreadPool(8)) {
            java.util.concurrent.Callable<HttpResponse<String>> call = () -> send("parallel", "{\"x\":1}", "");
            var results = threads.invokeAll(Collections.nCopies(20, call));
            int applied = 0;
            for (var future : results) {
                var response = future.get(); assertEquals(200, response.statusCode());
                if (response.body().contains("\"businessApplied\":true")) applied++;
            }
            assertEquals(1, applied);
        }
        assertOneEffect(20);
    }

    @Test void newReceiverAndStoreRetainDuplicateDecision() throws Exception {
        assertEquals(200, send("restart", "{\"x\":1}", "").statusCode());
        receiver.close();
        var restarted = new ReceiverStore(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        restarted.migrate();
        receiver = Receiver.start(0, 1200, log, restarted, dev.flowhook.signing.SigningTestSupport.SIGNER);
        assertTrue(send("restart", "{\"x\":1}", "").body().contains("\"duplicate\":true"));
        assertOneEffect(2);
    }

    @Test void lostResponseRetrySkipsDelayAndDoesNotRepeatBusiness() throws Exception {
        var signature = dev.flowhook.signing.SigningTestSupport.sign("lost", "{\"x\":1}");
        var timed = HttpRequest.newBuilder(request("lost", "{\"x\":1}", "?mode=delay").uri())
                .timeout(Duration.ofMillis(400)).header("Content-Type", "application/json")
                .header("X-FlowHook-Delivery-Id", "lost")
                .header("X-FlowHook-Timestamp", signature.timestamp()).header("X-FlowHook-Signature", signature.signature()).POST(HttpRequest.BodyPublishers.ofString("{\"x\":1}")).build();
        assertThrows(HttpTimeoutException.class, () -> client.send(timed, HttpResponse.BodyHandlers.ofString()));
        assertOneEffect(1);
        var duplicate = HttpRequest.newBuilder(timed.uri()).timeout(Duration.ofMillis(800))
                .header("Content-Type", "application/json").header("X-FlowHook-Delivery-Id", "lost")
                .header("X-FlowHook-Timestamp", signature.timestamp()).header("X-FlowHook-Signature", signature.signature())
                .POST(HttpRequest.BodyPublishers.ofString("{\"x\":1}")).build();
        var response = client.send(duplicate, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode()); assertTrue(response.body().contains("\"duplicate\":true"));
        assertOneEffect(2);
    }

    @Test void sameIdDifferentContentConflictsWithoutNewEffect() throws Exception {
        assertEquals(200, send("conflict", "{\"x\":1}", "").statusCode());
        assertEquals(409, send("conflict", "{\"x\":2}", "").statusCode());
        assertOneEffect(1);
    }

    @Test void jsonComparisonKeepsLargeNumbersExact() throws Exception {
        assertEquals(200, send("exact", "{\"n\":9007199254740993,\"a\":1}", "").statusCode());
        assertTrue(send("exact", "{\"a\":1.0,\"n\":9007199254740993}", "").body().contains("\"duplicate\":true"));
        assertEquals(409, send("exact", "{\"n\":9007199254740992,\"a\":1}", "").statusCode());
        assertOneEffect(2);
    }

    @Test void distinctDeliveryIdsAreDistinctBusinessEffects() throws Exception {
        assertEquals(200, send("destination-a", "{\"eventId\":\"same\"}", "").statusCode());
        assertEquals(200, send("destination-b", "{\"eventId\":\"same\"}", "").statusCode());
        assertEquals(2, count("SELECT count(*) FROM demo_receiver.business_effects"));
    }

    @Test void businessFailureRollsBackInboxAndCanBeRetried() throws Exception {
        execute("ALTER TABLE demo_receiver.business_effects ADD CONSTRAINT test_business_failure CHECK(false) NOT VALID");
        try {
            assertEquals(503, send("rollback", "{}", "").statusCode());
            assertEquals(0, count("SELECT count(*) FROM demo_receiver.receipts"));
            assertEquals(0, count("SELECT count(*) FROM demo_receiver.business_effects"));
        } finally { execute("ALTER TABLE demo_receiver.business_effects DROP CONSTRAINT test_business_failure"); }
        assertTrue(send("rollback", "{}", "").body().contains("\"businessApplied\":true"));
        assertOneEffect(1);
    }

    @ParameterizedTest @ValueSource(strings = {"broken", "[]", "null", "{\"x\":\"\\u0000\"}"})
    void invalidJsonNeverClaimsDelivery(String body) throws Exception {
        assertEquals(400, send("invalid", body, "").statusCode());
        assertEquals(0, count("SELECT count(*) FROM demo_receiver.receipts"));
    }

    @Test void failureModeDoesNotApplyOrClaimDelivery() throws Exception {
        assertEquals(500, send("failure", "{}", "?mode=failure").statusCode());
        assertEquals(0, count("SELECT count(*) FROM demo_receiver.receipts"));
        assertEquals(0, count("SELECT count(*) FROM demo_receiver.business_effects"));
    }

    @Test void duplicateIdHeadersAndInvalidUtf8AreRejected() throws Exception {
        var ambiguous = HttpRequest.newBuilder(request("one", "{}", "").uri())
                .header("Content-Type", "application/json").header("X-FlowHook-Delivery-Id", "one")
                .header("X-FlowHook-Delivery-Id", "two").POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        assertEquals(401, client.send(ambiguous, HttpResponse.BodyHandlers.ofString()).statusCode());
        byte[] invalidBytes = new byte[]{'{','"','x','"',':','"',(byte)0xc3,'"','}'};
        var signedInvalid = dev.flowhook.signing.SigningTestSupport.SIGNER.sign("utf8", invalidBytes);
        var invalid = HttpRequest.newBuilder(ambiguous.uri()).header("Content-Type", "application/json")
                .header("X-FlowHook-Delivery-Id", "utf8")
                .header("X-FlowHook-Timestamp", signedInvalid.timestamp()).header("X-FlowHook-Signature", signedInvalid.signature())
                .POST(HttpRequest.BodyPublishers.ofByteArray(invalidBytes)).build();
        assertEquals(400, client.send(invalid, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(0, count("SELECT count(*) FROM demo_receiver.receipts"));
    }
    @Test void tamperedAndStaleRequestsCannotTouchEvenAnExistingReceipt() throws Exception {
        assertEquals(200, send("protected", "{}", "").statusCode());
        String baselineLog = output.toString(java.nio.charset.StandardCharsets.UTF_8);
        var good = dev.flowhook.signing.SigningTestSupport.sign("protected", "{}");
        var old = new dev.flowhook.signing.WebhookSignature(new byte[32], java.time.Clock.offset(java.time.Clock.systemUTC(), Duration.ofMinutes(-10))).sign("protected", "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var future = new dev.flowhook.signing.WebhookSignature(new byte[32], java.time.Clock.offset(java.time.Clock.systemUTC(), Duration.ofMinutes(10))).sign("protected", "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] wrongKey = new byte[32]; wrongKey[0] = 1;
        var wrong = new dev.flowhook.signing.WebhookSignature(wrongKey, java.time.Clock.systemUTC()).sign("protected", "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String[][] invalid = {
                {"protected", good.timestamp(), good.signature(), "{\"tampered\":true}"},
                {"different", good.timestamp(), good.signature(), "{}"},
                {"protected", Long.toString(Long.parseLong(good.timestamp()) + 1), good.signature(), "{}"},
                {"protected", old.timestamp(), old.signature(), "{}"},
                {"protected", future.timestamp(), future.signature(), "{}"},
                {"protected", wrong.timestamp(), wrong.signature(), "{}"}
        };
        for (var item : invalid) {
            var request = HttpRequest.newBuilder(request("protected", "{}", "").uri())
                    .header("Content-Type", "application/json").header("X-FlowHook-Delivery-Id", item[0])
                    .header("X-FlowHook-Timestamp", item[1]).header("X-FlowHook-Signature", item[2])
                    .POST(HttpRequest.BodyPublishers.ofString(item[3])).build();
            assertEquals(401, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
        }
        assertOneEffect(1);
        assertEquals(baselineLog, output.toString(java.nio.charset.StandardCharsets.UTF_8));
    }

    @ParameterizedTest @ValueSource(strings = {"X-FlowHook-Timestamp", "X-FlowHook-Signature"})
    void duplicateSigningHeadersAreRejected(String name) throws Exception {
        var signed = dev.flowhook.signing.SigningTestSupport.sign("duplicates", "{}");
        var builder = HttpRequest.newBuilder(request("duplicates", "{}", "").uri())
                .header("Content-Type", "application/json").header("X-FlowHook-Delivery-Id", "duplicates")
                .header("X-FlowHook-Timestamp", signed.timestamp()).header("X-FlowHook-Signature", signed.signature());
        builder.header(name, name.endsWith("Timestamp") ? signed.timestamp() : signed.signature());
        var response = client.send(builder.POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode());
        assertEquals(0, output.size());
        assertEquals(0, count("SELECT count(*) FROM demo_receiver.receipts"));
    }

    @Test void failureModeStillRequiresValidSignature() throws Exception {
        var unsigned = HttpRequest.newBuilder(request("unsigned-failure", "{}", "?mode=failure").uri())
                .header("Content-Type", "application/json").header("X-FlowHook-Delivery-Id", "unsigned-failure")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        assertEquals(401, client.send(unsigned, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(0, output.size());
        assertEquals(0, count("SELECT count(*) FROM demo_receiver.receipts"));
    }

}
