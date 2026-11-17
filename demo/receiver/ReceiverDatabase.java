import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.time.Clock;
import dev.flowhook.signing.LocalWebhookKey;
import dev.flowhook.signing.WebhookSignature;

final class ReceiverDatabase {
    static ReceiverStore configuredStore() throws IOException {
        Properties local = localProperties();
        String url = value(local, "RECEIVER_DB_URL", value(local, "SPRING_DATASOURCE_URL",
                "jdbc:postgresql://localhost:" + value(local, "POSTGRES_PORT", "5433") + "/flowhook"));
        String user = value(local, "RECEIVER_DB_USER", value(local, "SPRING_DATASOURCE_USERNAME", "flowhook"));
        String password = value(local, "RECEIVER_DB_PASSWORD", value(local, "SPRING_DATASOURCE_PASSWORD", value(local, "POSTGRES_PASSWORD", null)));
        if (password == null) throw new IllegalArgumentException("수신기 DB 비밀번호 설정이 필요합니다.");
        return new ReceiverStore(url, user, password);
    }

    static WebhookSignature configuredSignature() throws IOException {
        return new WebhookSignature(LocalWebhookKey.load(value(localProperties(), "FLOWHOOK_SIGNING_KEY", "")), Clock.systemUTC());
    }

    private static Properties localProperties() throws IOException {
        Properties local = new Properties();
        Path file = Path.of(".env");
        if (Files.exists(file)) {
            try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { local.load(reader); }
        }
        return local;
    }

    private static String value(Properties local, String name, String fallback) {
        String environment = System.getenv(name);
        return environment != null ? environment : local.getProperty(name, fallback);
    }
}
