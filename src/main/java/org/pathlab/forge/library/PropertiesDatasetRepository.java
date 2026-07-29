package org.pathlab.forge.library;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

public final class PropertiesDatasetRepository implements DatasetRepository {
    private static final String PREFIX = "dataset.";
    private final Path storePath;
    private final Map<String, LocalDataset> datasets = new LinkedHashMap<>();

    public PropertiesDatasetRepository(Path storePath) throws IOException {
        this.storePath = storePath.toAbsolutePath().normalize();
        load();
    }

    @Override
    public synchronized List<LocalDataset> list() {
        return datasets.values().stream()
                .sorted(Comparator.comparing(LocalDataset::displayName)
                        .thenComparing(LocalDataset::id))
                .toList();
    }

    @Override
    public synchronized Optional<LocalDataset> find(String id) {
        return Optional.ofNullable(datasets.get(id));
    }

    @Override
    public synchronized Optional<LocalDataset> findBySourcePath(String sourcePath) {
        return datasets.values().stream()
                .filter(dataset -> dataset.sourcePath().equals(sourcePath))
                .findFirst();
    }

    @Override
    public synchronized void save(LocalDataset dataset) throws IOException {
        datasets.put(dataset.id(), dataset);
        persist();
    }

    @Override
    public synchronized void delete(String id) throws IOException {
        datasets.remove(id);
        persist();
    }

    private void load() throws IOException {
        if (!Files.isRegularFile(storePath)) {
            return;
        }
        var properties = new Properties();
        try (InputStream input = Files.newInputStream(storePath)) {
            properties.load(input);
        }
        var ids = new ArrayList<String>();
        for (var key : properties.stringPropertyNames()) {
            if (key.startsWith(PREFIX) && key.endsWith(".displayName")) {
                ids.add(key.substring(PREFIX.length(), key.length() - ".displayName".length()));
            }
        }
        for (var id : ids) {
            var key = PREFIX + id + ".";
            datasets.put(id, new LocalDataset(
                    id,
                    properties.getProperty(key + "displayName"),
                    properties.getProperty(key + "sourcePath"),
                    Long.parseLong(properties.getProperty(key + "sourceBytes")),
                    DatasetFormat.valueOf(properties.getProperty(key + "format")),
                    DatasetStatus.valueOf(properties.getProperty(key + "status")),
                    properties.getProperty(key + "detail", ""),
                    properties.getProperty(key + "outputPath", ""),
                    properties.getProperty(key + "sha256", "")));
        }
    }

    private void persist() throws IOException {
        Files.createDirectories(storePath.getParent());
        var properties = new Properties();
        for (var dataset : datasets.values()) {
            var key = PREFIX + dataset.id() + ".";
            properties.setProperty(key + "displayName", dataset.displayName());
            properties.setProperty(key + "sourcePath", dataset.sourcePath());
            properties.setProperty(key + "sourceBytes", Long.toString(dataset.sourceBytes()));
            properties.setProperty(key + "format", dataset.format().name());
            properties.setProperty(key + "status", dataset.status().name());
            properties.setProperty(key + "detail", dataset.detail());
            properties.setProperty(key + "outputPath", dataset.outputPath());
            properties.setProperty(key + "sha256", dataset.sha256());
        }
        var partial = storePath.resolveSibling(storePath.getFileName() + ".partial");
        try (OutputStream output = Files.newOutputStream(partial)) {
            properties.store(output, "PathLab Forge local library");
        }
        try {
            Files.move(
                    partial,
                    storePath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(partial, storePath, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
