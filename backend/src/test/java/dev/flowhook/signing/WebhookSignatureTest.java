package dev.flowhook.signing;

import java.nio.charset.StandardCharsets;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class WebhookSignatureTest {
    private static final Instant NOW = Instant.ofEpochSecond(1700000000);
    private static final byte[] BODY = "{\"text\":\"한글\",\"n\":1}".getBytes(StandardCharsets.UTF_8);
    private static byte[] key() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) key[i] = (byte) i;
        return key;
    }
    private WebhookSignature at(Instant time) { return new WebhookSignature(key(), Clock.fixed(time, ZoneOffset.UTC)); }

    @Test void matchesIndependentPythonHmacVectorForExactUtf8Body() {
        var signed = at(NOW).sign("42", BODY);
        assertEquals("1700000000", signed.timestamp());
        assertEquals("v1=a94a11270720d010bd0121bf1db626a5cb682d40183480ccc96dc688b56d2012", signed.signature());
        assertTrue(at(NOW).verify("42", signed.timestamp(), signed.signature(), BODY));
    }

    @ParameterizedTest @ValueSource(longs = {-300, 300})
    void acceptsBothFiveMinuteBoundaries(long seconds) {
        var signed = at(NOW.plusSeconds(seconds)).sign("42", BODY);
        assertTrue(at(NOW).verify("42", signed.timestamp(), signed.signature(), BODY));
    }

    @ParameterizedTest @ValueSource(longs = {-301, 301})
    void rejectsCorrectMacOutsideTimeWindow(long seconds) {
        var signed = at(NOW.plusSeconds(seconds)).sign("42", BODY);
        assertFalse(at(NOW).verify("42", signed.timestamp(), signed.signature(), BODY));
    }

    @Test void idTimestampAndExactBodyBytesAreAllProtected() {
        var signed = at(NOW).sign("42", BODY);
        assertFalse(at(NOW).verify("43", signed.timestamp(), signed.signature(), BODY));
        assertFalse(at(NOW).verify("42", "1700000001", signed.signature(), BODY));
        assertFalse(at(NOW).verify("42", signed.timestamp(), signed.signature(), "{}".getBytes(StandardCharsets.UTF_8)));
        assertFalse(at(NOW).verify("42", signed.timestamp(), signed.signature(), (new String(BODY, StandardCharsets.UTF_8) + " ").getBytes(StandardCharsets.UTF_8)));
        var wrong = new WebhookSignature(new byte[32], Clock.fixed(NOW, ZoneOffset.UTC));
        assertFalse(wrong.verify("42", signed.timestamp(), signed.signature(), BODY));
    }

    @ParameterizedTest @ValueSource(strings = {"", "-1", "01700000000", "1700000000.0", " 1700000000", "9223372036854775808", "9999999999999999999"})
    void rejectsMalformedAndOverflowTimestamps(String timestamp) {
        var signed = at(NOW).sign("42", BODY);
        assertFalse(at(NOW).verify("42", timestamp, signed.signature(), BODY));
    }

    @ParameterizedTest @ValueSource(strings = {"", "v2=abcd", "v1=abcd", "v1=zzzz", "v1="})
    void rejectsMalformedSignature(String signature) {
        assertFalse(at(NOW).verify("42", "1700000000", signature, BODY));
    }

    @Test void recomputesTimestampAndMacForNewAttemptTime() {
        var first = at(NOW).sign("42", BODY);
        var second = at(NOW.plusSeconds(5)).sign("42", BODY);
        assertNotEquals(first.timestamp(), second.timestamp());
        assertNotEquals(first.signature(), second.signature());
        assertTrue(at(NOW.plusSeconds(5)).verify("42", second.timestamp(), second.signature(), BODY));
    }

    @Test void rejectsMissingHeadersAndInvalidId() {
        assertFalse(at(NOW).verify("42", null, null, BODY));
        assertFalse(at(NOW).verify("a\nb", "1700000000", "v1=" + "0".repeat(64), BODY));
        assertThrows(IllegalArgumentException.class, () -> at(NOW).sign("a\nb", BODY));
        assertThrows(IllegalArgumentException.class, () -> new WebhookSignature(new byte[1], Clock.systemUTC()));
    }
}
