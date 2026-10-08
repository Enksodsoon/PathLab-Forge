package org.pathlab.forge.runtime;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class DesktopStartupTest {
    @Test void failurePipeContainsOnlyAnAllowlistedCode() throws Exception {
        var bytes = new ByteArrayOutputStream();
        DesktopStartup.failed(new PrintStream(bytes), "DATA_LOCKED");
        assertTrue(bytes.toString(StandardCharsets.UTF_8).startsWith("PATHLAB_FORGE_FAILED "));
        assertThrows(IllegalArgumentException.class, () -> DesktopStartup.failed(System.out, "secret"));
    }
    @Test
    void readinessIsOnePrivateRecordAndRejectsRemoteOrigins() throws Exception {
        var bytes = new ByteArrayOutputStream();
        DesktopStartup.ready(new PrintStream(bytes), URI.create("http://127.0.0.1:4321/app"),
                URI.create("http://127.0.0.1:4321/launch?token=private"), "a".repeat(43));
        var record = bytes.toString(StandardCharsets.UTF_8);
        assertEquals(1, record.lines().count());
        assertTrue(record.startsWith("PATHLAB_FORGE_READY "));
        assertTrue(record.contains("\"protocol\":1"));
        assertThrows(IllegalArgumentException.class, () -> DesktopStartup.ready(System.out,
                URI.create("https://example.com"), URI.create("https://example.com/launch"), "a".repeat(43)));
    }

    @Test
    void ownerDisconnectStopsService() throws Exception {
        var stopped = new CountDownLatch(1);
        DesktopStartup.watchOwner(new ByteArrayInputStream(new byte[0]), stopped::countDown);
        assertTrue(stopped.await(2, TimeUnit.SECONDS));
    }
}
