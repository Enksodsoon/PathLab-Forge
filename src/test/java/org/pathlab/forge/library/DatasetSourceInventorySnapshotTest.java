package org.pathlab.forge.library;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DatasetSourceInventorySnapshotTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void verifiesPersistedSnapshotWithoutRecomputingContentDigest() throws Exception {
        var source = temporaryDirectory.resolve("case.vsi");
        Files.write(source, new byte[] {1, 2, 3});
        var companion = Files.createDirectories(temporaryDirectory.resolve("_case_"))
                .resolve("frame.ets");
        Files.write(companion, new byte[] {4, 5, 6});
        var inventory = DatasetSourceInventory.forVsi(source);

        assertTrue(DatasetSourceInventory.matchesSnapshot(source, inventory.serialized()));

        Files.setLastModifiedTime(
                companion,
                FileTime.fromMillis(Files.getLastModifiedTime(companion).toMillis() + 2_000));
        assertFalse(DatasetSourceInventory.matchesSnapshot(source, inventory.serialized()));
    }

    @Test
    void invalidatesDigestWhenACompanionIsAddedAfterVerification() throws Exception {
        var source = temporaryDirectory.resolve("case.vsi");
        Files.write(source, new byte[] {1, 2, 3});
        var companionRoot = Files.createDirectories(temporaryDirectory.resolve("_case_"));
        Files.write(companionRoot.resolve("frame.ets"), new byte[] {4, 5, 6});
        var digest = SourceDigest.compute(SourceSnapshot.forVsi(source));

        assertTrue(DatasetSourceInventory.matchesSnapshot(
                source, digest.serializedInventory()));

        Files.write(companionRoot.resolve("metadata.bin"), new byte[] {7});

        assertFalse(DatasetSourceInventory.matchesSnapshot(
                source, digest.serializedInventory()));
    }
}
