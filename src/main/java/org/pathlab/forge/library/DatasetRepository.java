package org.pathlab.forge.library;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

public interface DatasetRepository {
    List<LocalDataset> list();

    Optional<LocalDataset> find(String id);

    Optional<LocalDataset> findBySourcePath(String sourcePath);

    void save(LocalDataset dataset) throws IOException;

    default LocalDataset update(String id, UnaryOperator<LocalDataset> change) throws IOException {
        var current = find(id).orElseThrow(() -> new IllegalArgumentException("Dataset was not found"));
        var updated = change.apply(current);
        save(updated);
        return updated;
    }

    void delete(String id) throws IOException;

    default List<ConversionQueueEntry> listQueueEntries() {
        return List.of();
    }

    default void saveQueueEntry(ConversionQueueEntry entry) throws IOException {}

    default void deleteQueueEntry(String datasetId) throws IOException {}
}
