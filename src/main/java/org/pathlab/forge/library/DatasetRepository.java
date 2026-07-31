package org.pathlab.forge.library;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

public interface DatasetRepository {
    List<LocalDataset> list();

    Optional<LocalDataset> find(String id);

    Optional<LocalDataset> findBySourcePath(String sourcePath);

    void save(LocalDataset dataset) throws IOException;

    void delete(String id) throws IOException;

    default List<ConversionQueueEntry> listQueueEntries() {
        return List.of();
    }

    default void saveQueueEntry(ConversionQueueEntry entry) throws IOException {}

    default void deleteQueueEntry(String datasetId) throws IOException {}
}
