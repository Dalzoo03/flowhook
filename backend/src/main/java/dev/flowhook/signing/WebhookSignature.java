package dev.flowhook.signing;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class WebhookSignature {
    private final byte[] key;
    private final Clock clock;

    public WebhookSignature(byte[] key, Clock clock) {
        if (key == null || key.length != 32) throw new IllegalArgumentException("Signing key must contain 32 bytes");
        this.key = key.clone();
        this.clock = java.util.Objects.requireNonNull(clock);
    }

    public SignedHeaders sign(String deliveryId, byte[] body) {
        if (!validId(deliveryId)) throw new IllegalArgumentException("Invalid delivery ID");
        String timestamp = Long.toString(clock.instant().getEpochSecond());
        return new SignedHeaders(timestamp, "v1=" + HexFormat.of().formatHex(mac(deliveryId, timestamp, body)));
    }

    public boolean verify(String deliveryId, String timestamp, String signature, byte[] body) {
        if (!validId(deliveryId) || timestamp == null || !timestamp.matches("0|[1-9][0-9]{0,18}")
                || signature == null || !signature.matches("v1=[0-9a-f]{64}")) return false;
        long sent;
        try { sent = Long.parseLong(timestamp); }
        catch (NumberFormatException invalid) { return false; }
        long now = clock.instant().getEpochSecond();
        if (sent < now - 300 || sent > now + 300) return false;
        return MessageDigest.isEqual(mac(deliveryId, timestamp, body), HexFormat.of().parseHex(signature.substring(3)));
    }

    private byte[] mac(String deliveryId, String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update(("v1\n" + timestamp + "\n" + deliveryId + "\n").getBytes(StandardCharsets.UTF_8));
            return mac.doFinal(body);
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("HMAC-SHA256 unavailable");
        }
    }

    private boolean validId(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    }

    public record SignedHeaders(String timestamp, String signature) { }
}
