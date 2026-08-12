package org.pathlab.forge.viewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqliteViewerSyncStoreTest {
    @TempDir
    Path temp;

    @Test
    void persistsRemoteIdentityDirtyFieldsCursorAndDownloadOffset() throws Exception {
        var database = temp.resolve("sync.db");
        var partial = temp.resolve("slide.partial");
        var remote = new ViewerRemoteSlide(
                "remote-1", "Slide one", "folder-1", "ready_private",
                11, 12, 13, 14, "thumbnail", "tiles", Instant.parse("2026-08-12T00:00:00Z"));

        try (var store = new SqliteViewerSyncStore(database)) {
            store.upsertRemote(remote);
            store.markDirty("remote-1", Set.of("displayName", "folderId"));
            store.beginDownload("remote-1", partial, 100, "a".repeat(64));
            store.advanceDownload("remote-1", 40);
            store.saveCursor(9);
            store.recordConflict(new ViewerSyncConflict(
                    "remote-1", "displayName", "Local", "Remote", 11,
                    Instant.parse("2026-08-12T00:01:00Z")));
        }

        try (var store = new SqliteViewerSyncStore(database)) {
            var record = store.find("remote-1").orElseThrow();
            assertEquals(Set.of("displayName", "folderId"), record.dirtyFields());
            assertEquals(40, record.downloadOffset());
            assertEquals(partial.toAbsolutePath().normalize(), record.partialPath());
            assertEquals(9, store.cursor());
            assertEquals(1, store.conflicts().size());
            assertTrue(store.conflicts().get(0).unresolved());
        }
    }
}
