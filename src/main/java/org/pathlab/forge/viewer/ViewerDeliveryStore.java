package org.pathlab.forge.viewer;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

public interface ViewerDeliveryStore extends AutoCloseable {
    void save(ViewerDeliveryJob job) throws IOException;

    Optional<ViewerDeliveryJob> find(String id) throws IOException;

    List<ViewerDeliveryJob> resumable() throws IOException;

    @Override
    void close() throws IOException;
}
