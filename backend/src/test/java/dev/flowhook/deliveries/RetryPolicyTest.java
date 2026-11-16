package dev.flowhook.deliveries;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class RetryPolicyTest {
    private final RetryPolicy policy = new RetryPolicy();
    private final Instant now = Instant.parse("2026-11-16T00:00:00Z");

    private LocalWebhookSender.SendResult result(String outcome, Integer code, String header) {
        return new LocalWebhookSender.SendResult(outcome, code, 1, new byte[0], false, header);
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 429, 500, 503, 599})
    void transientHttpFailuresAreScheduledUntilFifthAttempt(int code) {
        assertEquals("RETRY_WAIT", policy.decide(result("HTTP_ERROR", code, null), 1, now).status());
        var terminal = policy.decide(result("HTTP_ERROR", code, "600"), 5, now);
        assertEquals("DEAD", terminal.status());
        assertNull(terminal.delayMs());
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 400, 401, 404, 422})
    void permanentHttpFailuresAreNotRetried(int code) {
        for (String outcome : new String[]{"HTTP_ERROR", "TIMEOUT", "NETWORK_ERROR"}) {
            var decision = policy.decide(result(outcome, code, null), 1, now);
            assertEquals("DEAD", decision.status());
            assertNull(decision.delayMs());
        }
    }

    @Test void classifiesNetworkTimeoutBlockedUnknownAndSuccess() {
        for (String outcome : new String[]{"TIMEOUT", "NETWORK_ERROR"}) {
            assertEquals("RETRY_WAIT", policy.decide(result(outcome, null, null), 1, now).status());
            assertEquals("DEAD", policy.decide(result(outcome, null, null), 5, now).status());
        }
        for (String outcome : new String[]{"URL_BLOCKED", "UNKNOWN"}) {
            assertEquals("DEAD", policy.decide(result(outcome, null, null), 1, now).status());
        }
        var success = policy.decide(result("SUCCESS", 200, null), 5, now);
        assertEquals("SUCCEEDED", success.status());
        assertNull(success.delayMs());
    }

    @Test void exponentialBackoffIsWithinTwentyPercentJitter() {
        for (int attempt = 1; attempt < 5; attempt++) {
            long base = 5000L << (attempt - 1);
            for (int sample = 0; sample < 20; sample++) {
                long delay = policy.decide(result("HTTP_ERROR", 500, null), attempt, now).delayMs();
                assertTrue(delay >= base * 8 / 10 && delay <= base * 12 / 10);
            }
        }
    }

    @Test void retryAfterSecondsAndDateAreCappedAndDoNotShortenBackoff() {
        for (String header : new String[]{"600", "99999999999999999999999999999"}) {
            assertEquals(600000L, policy.decide(result("HTTP_ERROR", 429, header), 1, now).delayMs());
        }
        assertEquals(120000L, policy.decide(result("HTTP_ERROR", 429, "120"), 1, now).delayMs());
        String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(now.plusSeconds(120).atOffset(ZoneOffset.UTC));
        assertEquals(120000L, policy.decide(result("HTTP_ERROR", 429, date), 1, now).delayMs());
        String far = DateTimeFormatter.RFC_1123_DATE_TIME.format(now.plusSeconds(3600).atOffset(ZoneOffset.UTC));
        assertEquals(600000L, policy.decide(result("HTTP_ERROR", 429, far), 1, now).delayMs());
        for (String ignored : new String[]{"0", "-1", "bad", "Mon, 16 Nov 2020 00:00:00 GMT", "1.5"}) {
            long delay = policy.decide(result("HTTP_ERROR", 429, ignored), 1, now).delayMs();
            assertTrue(delay >= 4000 && delay <= 6000);
        }
    }
}
