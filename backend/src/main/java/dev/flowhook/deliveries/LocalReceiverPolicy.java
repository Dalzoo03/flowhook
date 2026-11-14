package dev.flowhook.deliveries;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LocalReceiverPolicy {
    private final boolean enabled;
    private final int port;

    public LocalReceiverPolicy(@Value("${flowhook.delivery.local-receiver-enabled:false}") boolean enabled,
                               @Value("${flowhook.delivery.local-receiver-port:9090}") int port) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid local receiver port");
        this.enabled = enabled;
        this.port = port;
    }

    public boolean allows(String value) {
        if (!enabled || value == null) return false;
        try {
            URI uri = new URI(value);
            return "http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost())
                    && uri.getPort() == port && uri.getRawPath().equals("/webhooks")
                    && uri.getRawUserInfo() == null && uri.getRawFragment() == null
                    && (uri.getRawQuery() == null || Set.of("mode=success", "mode=failure", "mode=delay").contains(uri.getRawQuery()));
        } catch (URISyntaxException exception) { return false; }
    }
}
