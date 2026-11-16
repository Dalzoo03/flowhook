package dev.flowhook.deliveries;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

@Component
public class RetryPolicy {
    static final int MAX_ATTEMPTS = 5;

    Decision decide(LocalWebhookSender.SendResult result, int attemptCount, Instant now) {
        if (attemptCount < 1 || attemptCount > MAX_ATTEMPTS) throw new IllegalArgumentException("Invalid attempt count");
        if (result.outcome().equals("SUCCESS")) return new Decision("SUCCEEDED", null);
        Integer code = result.httpStatus();
        boolean retryable = retryable(result);
        if (!retryable || attemptCount >= MAX_ATTEMPTS) return new Decision("DEAD", null);
        long minimum = code != null && code == 429 ? retryAfterMillis(result.retryAfter(), now) : 0;
        return retry(attemptCount, minimum);
    }

    Decision afterLeaseExpiry(int attemptCount) {
        if (attemptCount < 1 || attemptCount > MAX_ATTEMPTS) throw new IllegalArgumentException("Invalid attempt count");
        return retry(attemptCount, 0);
    }

    private Decision retry(int attemptCount, long minimumDelay) {
        if (attemptCount >= MAX_ATTEMPTS) return new Decision("DEAD", null);
        long base = 5000L << (attemptCount - 1);
        long delay = ThreadLocalRandom.current().nextLong(base * 8 / 10, base * 12 / 10 + 1);
        return new Decision("RETRY_WAIT", Math.max(delay, minimumDelay));
    }

    private boolean retryable(LocalWebhookSender.SendResult result) {
        boolean transportError = result.outcome().equals("TIMEOUT") || result.outcome().equals("NETWORK_ERROR");
        if (!transportError && !result.outcome().equals("HTTP_ERROR")) return false;
        Integer code = result.httpStatus();
        if (code != null && code >= 300) return code == 408 || code == 429 || code >= 500 && code < 600;
        return transportError;
    }

    private long retryAfterMillis(String header, Instant now) {
        if (header == null) return 0;
        String value = header.strip();
        if (value.matches("[0-9]+")) {
            try { return Math.min(Long.parseLong(value), 600) * 1000; }
            catch (NumberFormatException overflow) { return 600000; }
        }
        try {
            Instant date = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            Duration wait = Duration.between(now, date);
            if (wait.isNegative()) return 0;
            if (wait.compareTo(Duration.ofMinutes(10)) >= 0) return 600000;
            return wait.getSeconds() * 1000 + (wait.getNano() + 999999) / 1000000;
        } catch (DateTimeParseException invalid) { return 0; }
    }

    record Decision(String status, Long delayMs) { }
}
