package org.pathlab.forge.conversion;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class StageCheckpointStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void atomicallyPersistsLatestVerifiedStageForRestartResume() throws Exception {
        var store = new StageCheckpointStore(temporaryDirectory);
        var checkpoint = new StageCheckpoint(
                "artifact-1",
                "configuration-1",
                "source-1",
                StageCheckpoint.Stage.OME_VERIFIED,
                5,
                5,
                1234);

        store.save(checkpoint);

        assertEquals(checkpoint, store.load().orElseThrow());
    }
}
