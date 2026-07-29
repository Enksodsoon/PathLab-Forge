package org.pathlab.forge.viewer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@EnabledOnOs(OS.WINDOWS)
final class WindowsCredentialStoreTest {
    @Test
    void roundTripsCredentialWithoutFilesystemPersistence() throws Exception {
        var store = new WindowsCredentialStore();
        var original = store.read();
        try {
            store.write("https://viewer.local\nnon-production-test-token");
            assertEquals(
                    "https://viewer.local\nnon-production-test-token",
                    store.read().orElseThrow());
        } finally {
            if (original.isPresent()) {
                store.write(original.orElseThrow());
            } else {
                store.delete();
            }
        }
    }
}
