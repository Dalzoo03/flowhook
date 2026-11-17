package dev.flowhook.signing;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Collections;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LocalWebhookKeyTest {
    @TempDir Path directory;

    @Test void createsPrivatePersistentKeyAndConcurrentReadersAgree() throws Exception {
        Path file = directory.resolve("keys/signing.key");
        byte[] initial = LocalWebhookKey.loadFile(file);
        assertEquals(32, initial.length);
        assertArrayEquals(initial, LocalWebhookKey.loadFile(file));
        try (var threads = Executors.newFixedThreadPool(4)) {
            Callable<byte[]> read = () -> LocalWebhookKey.loadFile(file);
            for (var result : threads.invokeAll(Collections.nCopies(8, read))) assertArrayEquals(initial, result.get());
        }
        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
        }
    }

    @Test void rejectsMalformedExplicitKeyRatherThanPrintingIt() {
        var error = assertThrows(IllegalArgumentException.class, () -> LocalWebhookKey.load("invalid-key-value"));
        assertFalse(error.getMessage().contains("invalid-key-value"));
        assertThrows(IllegalArgumentException.class, () -> LocalWebhookKey.load(" "));
    }

    @Test void refusesSymlinkInsteadOfReadingTarget() throws Exception {
        Path target = directory.resolve("target");
        Files.writeString(target, "unrelated");
        Path link = directory.resolve("link");
        Files.createSymbolicLink(link, target);
        assertThrows(java.io.IOException.class, () -> LocalWebhookKey.loadFile(link));
        assertEquals("unrelated", Files.readString(target));
    }
    @Test void twoIndependentJvmsAgreeDuringFirstKeyCreation() throws Exception {
        Path file = directory.resolve("shared/signing.key");
        Path gate = directory.resolve("gate");
        Path firstReady = directory.resolve("first-ready"), secondReady = directory.resolve("second-ready");
        Path firstHash = directory.resolve("first-hash"), secondHash = directory.resolve("second-hash");
        String classpath = Path.of(LocalWebhookKey.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                + java.io.File.pathSeparator + Path.of(SigningKeyProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var first = new ProcessBuilder(javaExecutable, "-cp", classpath, SigningKeyProbe.class.getName(), file.toString(), gate.toString(), firstReady.toString(), firstHash.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        var second = new ProcessBuilder(javaExecutable, "-cp", classpath, SigningKeyProbe.class.getName(), file.toString(), gate.toString(), secondReady.toString(), secondHash.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
            while (!Files.exists(firstReady) || !Files.exists(secondReady)) {
                if (System.nanoTime() > deadline) fail("Key probes did not become ready");
                Thread.sleep(10);
            }
            assertFalse(Files.exists(file));
            Files.writeString(gate, "start");
            assertTrue(first.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(second.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0, first.exitValue()); assertEquals(0, second.exitValue());
            assertArrayEquals(Files.readAllBytes(firstHash), Files.readAllBytes(secondHash));
        } finally {
            if (first.isAlive()) first.destroyForcibly();
            if (second.isAlive()) second.destroyForcibly();
        }
    }

}
