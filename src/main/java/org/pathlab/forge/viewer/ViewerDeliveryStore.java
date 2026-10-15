package org.pathlab.forge.viewer;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

public interface ViewerDeliveryStore extends AutoCloseable {
    default void bindConnection(String key) throws IOException { }

    void save(ViewerDeliveryJob job) throws IOException;

    Optional<ViewerDeliveryJob> find(String id) throws IOException;

    default Optional<ViewerDeliveryJob> findLatestByArtifact(String revisionId) throws IOException {
        return resumable().stream().filter(job -> job.artifactRevisionId().equals(revisionId))
                .max(java.util.Comparator.comparing(ViewerDeliveryJob::updatedAt));
    }

    List<ViewerDeliveryJob> resumable() throws IOException;

    @Override
    void close() throws IOException;
}
