package org.pathlab.forge.conversion;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

final class SeriesMetadataCache {
    private static final String VERSION = "1";
    private final Path managedRoot;

    SeriesMetadataCache(Path managedRoot) {
        this.managedRoot = managedRoot.toAbsolutePath().normalize();
    }

    Optional<List<SeriesInfo>> load(String datasetId, String sourceFingerprint)
            throws IOException {
        var file = cacheFile(datasetId);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        var properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
        } catch (IllegalArgumentException error) {
            return Optional.empty();
        }
        if (!VERSION.equals(properties.getProperty("version"))
                || !sourceFingerprint.equals(properties.getProperty("sourceFingerprint"))) {
            return Optional.empty();
        }
        try {
            var count = Integer.parseInt(properties.getProperty("count", "0"));
            if (count <= 0 || count > 10_000) {
                return Optional.empty();
            }
            var series = new ArrayList<SeriesInfo>(count);
            for (var position = 0; position < count; position++) {
                var prefix = "series." + position + ".";
                series.add(new SeriesInfo(
                        integer(properties, prefix + "index"),
                        properties.getProperty(prefix + "name", ""),
                        integer(properties, prefix + "width"),
                        integer(properties, prefix + "height"),
                        integer(properties, prefix + "channels"),
                        integer(properties, prefix + "sizeZ"),
                        integer(properties, prefix + "sizeT"),
                        properties.getProperty(prefix + "pixelType", ""),
                        decimal(properties, prefix + "physicalSizeX"),
                        decimal(properties, prefix + "physicalSizeY"),
                        properties.getProperty(prefix + "physicalUnit", ""),
                        integer(properties, prefix + "resolutionCount")));
            }
            return Optional.of(List.copyOf(series));
        } catch (IllegalArgumentException error) {
            return Optional.empty();
        }
    }

    void save(String datasetId, String sourceFingerprint, List<SeriesInfo> series)
            throws IOException {
        if (series.isEmpty()) {
            throw new IllegalArgumentException("Series metadata cannot be empty");
        }
        var file = cacheFile(datasetId);
        Files.createDirectories(file.getParent());
        var properties = new Properties();
        properties.setProperty("version", VERSION);
        properties.setProperty("sourceFingerprint", sourceFingerprint);
        properties.setProperty("count", Integer.toString(series.size()));
        for (var position = 0; position < series.size(); position++) {
            var item = series.get(position);
            var prefix = "series." + position + ".";
            properties.setProperty(prefix + "index", Integer.toString(item.index()));
            properties.setProperty(prefix + "name", item.name());
            properties.setProperty(prefix + "width", Integer.toString(item.width()));
            properties.setProperty(prefix + "height", Integer.toString(item.height()));
            properties.setProperty(prefix + "channels", Integer.toString(item.channels()));
            properties.setProperty(prefix + "sizeZ", Integer.toString(item.sizeZ()));
            properties.setProperty(prefix + "sizeT", Integer.toString(item.sizeT()));
            properties.setProperty(prefix + "pixelType", item.pixelType());
            properties.setProperty(
                    prefix + "physicalSizeX", Double.toString(item.physicalSizeX()));
            properties.setProperty(
                    prefix + "physicalSizeY", Double.toString(item.physicalSizeY()));
            properties.setProperty(prefix + "physicalUnit", item.physicalUnit());
            properties.setProperty(
                    prefix + "resolutionCount", Integer.toString(item.resolutionCount()));
        }
        var partial = file.resolveSibling(file.getFileName() + ".partial");
        try (OutputStream output = Files.newOutputStream(partial)) {
            properties.store(output, "PathLab Forge verified series metadata");
        }
        try {
            Files.move(
                    partial,
                    file,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    Path thumbnailFile(String datasetId, String sourceFingerprint, int seriesIndex) {
        var fingerprintKey = sourceFingerprint.length() > 24
                ? sourceFingerprint.substring(0, 24)
                : sourceFingerprint;
        var file = datasetRoot(datasetId)
                .resolve("inspection")
                .resolve("series-v1-" + fingerprintKey)
                .resolve("series-" + seriesIndex + ".jpg")
                .normalize();
        if (!file.startsWith(datasetRoot(datasetId))) {
            throw new IllegalArgumentException("Thumbnail path escapes managed storage");
        }
        return file;
    }

    private Path cacheFile(String datasetId) {
        return datasetRoot(datasetId).resolve("inspection").resolve("series-v1.properties");
    }

    private Path datasetRoot(String datasetId) {
        var root = managedRoot.resolve(datasetId).normalize();
        if (!root.startsWith(managedRoot)) {
            throw new IllegalArgumentException("Dataset identifier escapes managed storage");
        }
        return root;
    }

    private static int integer(Properties properties, String key) {
        return Integer.parseInt(properties.getProperty(key, ""));
    }

    private static double decimal(Properties properties, String key) {
        return Double.parseDouble(properties.getProperty(key, "0"));
    }
}
