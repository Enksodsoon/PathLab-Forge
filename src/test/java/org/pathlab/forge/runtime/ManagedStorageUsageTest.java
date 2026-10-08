package org.pathlab.forge.runtime;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ManagedStorageUsageTest {
    @TempDir Path root;
    @Test void measuresMetadataAndArtifactsWithoutInventingCapacityAndDisclosesBounds() throws Exception {
        Files.write(root.resolve("forge.db"), new byte[7]);
        Files.createDirectories(root.resolve("managed/results"));
        Files.write(root.resolve("managed/results/slide.ome.tif"), new byte[11]);
        var measured = ManagedStorageUsage.measure(root);
        assertEquals(18, measured.bytes()); assertEquals(2, measured.files()); assertTrue(measured.complete());
        assertFalse(ManagedStorageUsage.measure(root, 1, Long.MAX_VALUE).complete());
        assertFalse(ManagedStorageUsage.measure(root, Long.MAX_VALUE, 0).complete());
        assertFalse(ManagedStorageUsage.measure(root.resolve("missing")).complete());
        assertArrayEquals(new byte[11], Files.readAllBytes(root.resolve("managed/results/slide.ome.tif")));
    }
}
