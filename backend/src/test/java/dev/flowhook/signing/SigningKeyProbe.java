package dev.flowhook.signing;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

// 별도 JVM의 동시 첫 실행을 검증하는 테스트 전용 진입점이다.
public class SigningKeyProbe {
    public static void main(String[] args) throws Exception {
        Files.writeString(Path.of(args[2]), "ready");
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (!Files.exists(Path.of(args[1]))) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("Gate timeout");
            Thread.sleep(5);
        }
        byte[] key = LocalWebhookKey.loadFile(Path.of(args[0]));
        Files.write(Path.of(args[3]), MessageDigest.getInstance("SHA-256").digest(key));
    }
}
