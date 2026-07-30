package org.pathlab.forge.runtime;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DataRootLockTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsSecondOwnerUntilFirstOwnerCloses() throws Exception {
        try (var first = DataRootLock.acquire(temporaryDirectory)) {
            assertTrue(first.toString().contains("DataRootLock"));
            assertThrows(IOException.class, () -> DataRootLock.acquire(temporaryDirectory));
        }

        try (var recovered = DataRootLock.acquire(temporaryDirectory)) {
            assertTrue(recovered.toString().contains("DataRootLock"));
        }
    }
}
