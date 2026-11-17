package dev.flowhook.signing;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

public final class LocalWebhookKey {
    private LocalWebhookKey() { }

    public static byte[] load(String encoded) throws IOException {
        if (encoded != null && !encoded.isEmpty()) return decode(encoded);
        String configured = System.getenv("FLOWHOOK_SIGNING_KEY_FILE");
        Path path = configured == null || configured.isBlank()
                ? projectRoot().resolve(".local/webhook-signing.key") : Path.of(configured).toAbsolutePath();
        return loadFile(path);
    }

    public static synchronized byte[] loadFile(Path path) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        FileAttribute<?>[] attributes = Files.getFileStore(parent).supportsFileAttributeView("posix")
                ? new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))}
                : new FileAttribute<?>[0];
        try (var channel = FileChannel.open(path, Set.of(StandardOpenOption.CREATE, StandardOpenOption.READ,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS), attributes);
             var lock = channel.lock()) {
            if (Files.getFileStore(path).supportsFileAttributeView("posix")
                    && !Files.getPosixFilePermissions(path).equals(PosixFilePermissions.fromString("rw-------"))) {
                throw new IOException("Signing key file requires owner-only read/write permissions");
            }
            if (channel.size() == 0) {
                byte[] key = new byte[32];
                new SecureRandom().nextBytes(key);
                var bytes = ByteBuffer.wrap(Base64.getEncoder().encode(key));
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
                return key;
            }
            if (channel.size() > 128) throw new IOException("Invalid signing key file");
            var bytes = ByteBuffer.allocate((int) channel.size());
            while (bytes.hasRemaining() && channel.read(bytes) != -1) { }
            return decode(new String(bytes.array(), java.nio.charset.StandardCharsets.US_ASCII).strip());
        }
    }

    private static byte[] decode(String value) {
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            if (decoded.length != 32) throw new IllegalArgumentException();
            return decoded;
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Signing key must be Base64 encoding of 32 bytes");
        }
    }

    private static Path projectRoot() throws IOException {
        for (Path path = Path.of("").toAbsolutePath(); path != null; path = path.getParent()) {
            if (Files.isDirectory(path.resolve("backend")) && Files.isRegularFile(path.resolve("scripts/receiver.sh"))) return path;
        }
        throw new IOException("Configure a signing key outside the local project");
    }
}
