package dev.flowhook.deliveries;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

@Component
public class LocalWebhookSender {
    private final LocalReceiverPolicy policy;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).proxy(HttpClient.Builder.NO_PROXY).build();

    public LocalWebhookSender(LocalReceiverPolicy policy) { this.policy = policy; }

    SendResult send(long deliveryId, String url, String payload) {
        long started = System.nanoTime();
        if (!policy.allows(url)) return result("URL_BLOCKED", null, new Body(new byte[0], false), started);
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("X-FlowHook-Delivery-Id", String.valueOf(deliveryId))
                .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
        var pending = client.sendAsync(request, ignored -> new LimitedBody());
        try {
            var response = pending.get(10, TimeUnit.SECONDS);
            return result(response.statusCode() >= 200 && response.statusCode() < 300 ? "SUCCESS" : "HTTP_ERROR",
                    response.statusCode(), response.body(), started);
        } catch (TimeoutException exception) {
            pending.cancel(true);
            return result("TIMEOUT", null, new Body(new byte[0], false), started);
        } catch (ExecutionException exception) {
            return result(exception.getCause() instanceof HttpTimeoutException ? "TIMEOUT" : "NETWORK_ERROR",
                    null, new Body(new byte[0], false), started);
        } catch (InterruptedException exception) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            return result("UNKNOWN", null, new Body(new byte[0], false), started);
        }
    }

    private SendResult result(String outcome, Integer code, Body body, long started) {
        return new SendResult(outcome, code, Duration.ofNanos(System.nanoTime() - started).toMillis(), body.bytes(), body.truncated());
    }

    @PreDestroy void close() { client.shutdownNow(); }

    record SendResult(String outcome, Integer httpStatus, long durationMs, byte[] body, boolean truncated) { }
    record Body(byte[] bytes, boolean truncated) { }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<Body> {
        private final CompletableFuture<Body> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        @Override public CompletionStage<Body> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                int count = Math.min(buffer.remaining(), 4096 - bytes.size());
                byte[] part = new byte[count];
                buffer.get(part);
                bytes.writeBytes(part);
                if (buffer.hasRemaining()) {
                    subscription.cancel();
                    body.complete(new Body(bytes.toByteArray(), true));
                    return;
                }
            }
            subscription.request(1);
        }
        @Override public void onComplete() { body.complete(new Body(bytes.toByteArray(), false)); }
        @Override public void onError(Throwable error) { body.completeExceptionally(error); }
    }
}
