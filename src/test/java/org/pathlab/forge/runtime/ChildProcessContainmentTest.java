package org.pathlab.forge.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class ChildProcessContainmentTest {
    @Test
    void terminatesRegisteredChildTreeOnContainmentClose() throws Exception {
        var process = new ProcessBuilder(
                        "powershell.exe",
                        "-NoProfile",
                        "-Command",
                        "Start-Sleep -Seconds 30")
                .start();
        try (var containment = new ChildProcessContainment()) {
            containment.register(process);
        }

        process.waitFor(5, TimeUnit.SECONDS);
        assertFalse(process.isAlive());
    }
}
