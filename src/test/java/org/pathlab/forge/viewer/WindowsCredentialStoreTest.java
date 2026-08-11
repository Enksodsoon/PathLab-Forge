package org.pathlab.forge.viewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@EnabledOnOs(OS.WINDOWS)
final class WindowsCredentialStoreTest {
    @Test
    void roundTripsCredentialWithoutFilesystemPersistence() throws Exception {
        var store = new WindowsCredentialStore("PathLab Forge/Test/" + UUID.randomUUID());
        try {
            store.write("https://viewer.local\nnon-production-test-token");
            assertEquals(
                    "https://viewer.local\nnon-production-test-token",
                    store.read().orElseThrow());
        } finally {
            store.delete();
        }
    }

    @Test
    void rejectsUnsafeCredentialTargets() {
        assertThrows(IllegalArgumentException.class, () -> new WindowsCredentialStore(" "));
        assertThrows(IllegalArgumentException.class, () -> new WindowsCredentialStore("x".repeat(241)));
    }
}
