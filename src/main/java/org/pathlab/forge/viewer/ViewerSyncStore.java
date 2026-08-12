package org.pathlab.forge.viewer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface ViewerSyncStore extends AutoCloseable {
    void upsertRemote(ViewerRemoteSlide slide) throws IOException;
    Optional<ViewerSyncRecord> find(String slideId) throws IOException;
    List<ViewerSyncRecord> all() throws IOException;
    void markDirty(String slideId, Set<String> fields) throws IOException;
    void beginDownload(String slideId, Path partialPath, long bytes, String sha256) throws IOException;
    void advanceDownload(String slideId, long offset) throws IOException;
    void saveCursor(long cursor) throws IOException;
    long cursor() throws IOException;
    void recordConflict(ViewerSyncConflict conflict) throws IOException;
    List<ViewerSyncConflict> conflicts() throws IOException;
    @Override void close() throws IOException;
}
