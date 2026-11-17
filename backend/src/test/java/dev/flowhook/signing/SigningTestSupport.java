package dev.flowhook.signing;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;

public final class SigningTestSupport {
    public static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    public static final WebhookSignature SIGNER = new WebhookSignature(new byte[32], Clock.systemUTC());

    public static WebhookSignature.SignedHeaders sign(String id, String body) {
        return SIGNER.sign(id, body.getBytes(StandardCharsets.UTF_8));
    }
}
