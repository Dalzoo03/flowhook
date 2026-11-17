import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import dev.flowhook.signing.WebhookSignature;
import java.time.Clock;

public class ReceiverTest {
    private static final WebhookSignature SIGNER = new WebhookSignature(new byte[32], Clock.systemUTC());
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    public static void main(String[] args) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var receiver = Receiver.start(0, 150, new PrintStream(output, true, StandardCharsets.UTF_8), SIGNER)) {
            String base = "http://127.0.0.1:" + receiver.port();
            var missingId = CLIENT.send(HttpRequest.newBuilder(URI.create(base + "/webhooks"))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            check(missingId.statusCode() == 401, "delivery ID required");
            check(send(base, "", "{}").statusCode() == 200, "success mode");
            check(send(base, "?mode=failure", "{}").statusCode() == 500, "failure mode");
            long start = System.nanoTime();
            check(send(base, "?mode=delay", "{}").statusCode() == 200, "delay response");
            check(Duration.ofNanos(System.nanoTime() - start).toMillis() >= 120, "delay actually waits");
            check(send(base, "?mode=unknown", "{}").statusCode() == 400, "invalid mode");
            check(send(base, "?mode=delay&mode=success", "{}").statusCode() == 400, "ambiguous mode");
            check(send(base, "", "a".repeat(65536)).statusCode() == 200, "body exactly at limit");
            check(send(base, "", "a".repeat(65537)).statusCode() == 413, "body above limit");
            var get = CLIENT.send(HttpRequest.newBuilder(URI.create(base + "/webhooks")).GET().build(), HttpResponse.BodyHandlers.ofString());
            check(get.statusCode() == 405 && get.headers().firstValue("Allow").orElse("").equals("POST"), "method restriction");
            var missing = CLIENT.send(HttpRequest.newBuilder(URI.create(base + "/missing")).GET().build(), HttpResponse.BodyHandlers.ofString());
            check(missing.statusCode() == 404, "path restriction");
            var wrongType = CLIENT.send(HttpRequest.newBuilder(URI.create(base + "/webhooks"))
                    .header("Content-Type", "text/plain").POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            check(wrongType.statusCode() == 415, "content type restriction");
            var signature = SIGNER.sign("delivery-123", "{\"orderId\":1001}".getBytes(StandardCharsets.UTF_8));
            var request = HttpRequest.newBuilder(URI.create(base + "/webhooks"))
                    .header("Content-Type", "application/json").header("Authorization", "secret-test-value")
                    .header("X-FlowHook-Delivery-Id", "delivery-123")
                    .header("X-FlowHook-Timestamp", signature.timestamp()).header("X-FlowHook-Signature", signature.signature())
                    .POST(HttpRequest.BodyPublishers.ofString("{\"orderId\":1001}")).build();
            CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            String log = output.toString(StandardCharsets.UTF_8);
            check(log.contains("delivery-123") && log.contains("orderId"), "body and delivery header logged");
            check(!log.contains("secret-test-value"), "authorization header excluded");
            check(!log.contains(signature.signature()), "signature header excluded");
            try (var executor = Executors.newFixedThreadPool(2)) {
                var delayed = executor.submit(() -> send(base, "?mode=delay", "{}"));
                check(send(base, "?mode=failure", "{}").statusCode() == 500, "parallel modes stay independent");
                check(delayed.get().statusCode() == 200, "parallel delay completes");
            }
        }
        try {
            Receiver.start(0, -1, System.out, SIGNER);
            throw new AssertionError("negative delay accepted");
        } catch (IllegalArgumentException expected) {
            // 잘못된 설정은 서버를 열기 전에 거절한다.
        }
        System.out.println("Receiver checks passed (real loopback HTTP, no external services or DB).");
    }

    private static HttpResponse<String> send(String base, String query, String body) throws Exception {
        String id = java.util.UUID.randomUUID().toString();
        var signature = SIGNER.sign(id, body.getBytes(StandardCharsets.UTF_8));
        return CLIENT.send(HttpRequest.newBuilder(URI.create(base + "/webhooks" + query))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .header("X-FlowHook-Delivery-Id", id)
                .header("X-FlowHook-Timestamp", signature.timestamp()).header("X-FlowHook-Signature", signature.signature())
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
