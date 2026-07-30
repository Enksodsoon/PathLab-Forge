package org.pathlab.forge.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class ChildProcessContainmentTest {
    @Test
    void terminatesRegisteredChildTreeOnContainmentClose() throws Exception {
        var windows = System.getProperty("os.name", "").startsWith("Windows");
        var process = windows
                ? new ProcessBuilder(
                                "powershell.exe",
                                "-NoProfile",
                                "-Command",
                                "Start-Sleep -Seconds 30")
                        .start()
                : new ProcessBuilder("sh", "-c", "sleep 30").start();
        try (var containment = new ChildProcessContainment()) {
            containment.register(process);
        }

        process.waitFor(5, TimeUnit.SECONDS);
        assertFalse(process.isAlive());
    }
}
