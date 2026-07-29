package org.pathlab.forge.conversion;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.pathlab.forge.library.DatasetRepository;
import org.pathlab.forge.library.DatasetStatus;
import org.pathlab.forge.library.LocalDataset;

public final class ConversionService implements AutoCloseable {
    private final DatasetRepository repository;
    private final ConversionEngine engine;
    private final Path managedRoot;
    private final Map<String, List<SeriesInfo>> inspectedSeries = new ConcurrentHashMap<>();
    private final ExecutorService conversionExecutor = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "pathlab-forge-conversion");
        thread.setDaemon(true);
        return thread;
    });

    public ConversionService(
            DatasetRepository repository, ConversionEngine engine, Path managedRoot) {
        this.repository = repository;
        this.engine = engine;
        this.managedRoot = managedRoot.toAbsolutePath().normalize();
    }

    public ConversionEngine engine() {
        return engine;
    }

    public List<SeriesInfo> inspect(String id) throws IOException {
        var dataset = requireDataset(id);
        var inspecting = dataset.withConversion(
                DatasetStatus.INSPECTING,
                "Reading bounded image metadata with Bio-Formats",
                dataset.outputPath(),
                dataset.sha256(),
                dataset.selectedSeries(),
                dataset.width(),
                dataset.height(),
                dataset.downsample(),
                dataset.estimatedOutputBytes());
        repository.save(inspecting);
        try {
            var series = engine.inspect(Path.of(dataset.sourcePath()));
            inspectedSeries.put(id, series);
            var selected = series.stream()
                    .filter(SeriesInfo::isRgbPlane)
                    .max(Comparator.comparingLong(
                            item -> (long) item.width() * (long) item.height()))
                    .orElse(series.get(0));
            var ready = inspecting.withConversion(
                    DatasetStatus.READY_TO_CONVERT,
                    series.size() + " image series found; select a series and export",
                    "",
                    "",
                    selected.index(),
                    selected.width(),
                    selected.height(),
                    1,
                    OutputSizeEstimator.rgbPyramidUpperBound(
                            selected.width(), selected.height(), 1));
            repository.save(ready);
            return series;
        } catch (IOException | RuntimeException error) {
            repository.save(inspecting.withConversion(
                    DatasetStatus.FAILED,
                    concise(error.getMessage()),
                    "",
                    "",
                    -1,
                    0,
                    0,
                    1,
                    0));
            throw error;
        }
    }

    public List<SeriesInfo> series(String id) {
        return inspectedSeries.getOrDefault(id, List.of());
    }

    public LocalDataset selectSeries(String id, int seriesIndex, int downsample)
            throws IOException {
        if (downsample != 1) {
            throw new IllegalArgumentException(
                    "This build exports full resolution only; 2x/4x/8x is not enabled yet");
        }
        var dataset = requireDataset(id);
        var info = series(id).stream()
                .filter(item -> item.index() == seriesIndex)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Inspect this dataset before selecting a series"));
        if (!info.isRgbPlane()) {
            throw new IllegalArgumentException(
                    "Select a 2D RGB series (3 channels, one Z plane, one timepoint)");
        }
        var updated = dataset.withConversion(
                DatasetStatus.READY_TO_CONVERT,
                "Series " + info.index() + " selected for full-resolution RGB export",
                "",
                "",
                info.index(),
                info.width(),
                info.height(),
                downsample,
                OutputSizeEstimator.rgbPyramidUpperBound(
                        info.width(), info.height(), downsample));
        repository.save(updated);
        return updated;
    }

    public LocalDataset start(String id) throws IOException {
        var dataset = requireDataset(id);
        if (dataset.status() == DatasetStatus.CONVERTING
                || dataset.status() == DatasetStatus.VALIDATING) {
            return dataset;
        }
        if (dataset.selectedSeries() < 0) {
            throw new IllegalStateException("Inspect and select an image series first");
        }
        managedDirectory(dataset.id());
        var converting = dataset.withConversion(
                DatasetStatus.CONVERTING,
                "Exporting tiled RGB OME-BigTIFF with LZW compression",
                "",
                "",
                dataset.selectedSeries(),
                dataset.width(),
                dataset.height(),
                dataset.downsample(),
                dataset.estimatedOutputBytes());
        repository.save(converting);
        conversionExecutor.submit(() -> convert(converting));
        return converting;
    }

    private void convert(LocalDataset dataset) {
        var outputDirectory = managedDirectory(dataset.id());
        var output = outputDirectory.resolve("series-" + dataset.selectedSeries() + ".ome.btf");
        var partial = output.resolveSibling(
                "series-" + dataset.selectedSeries() + ".partial.ome.btf");
        try {
            Files.createDirectories(outputDirectory);
            Files.deleteIfExists(partial);
            engine.convert(Path.of(dataset.sourcePath()), dataset.selectedSeries(), partial);
            repository.save(dataset.withConversion(
                    DatasetStatus.VALIDATING,
                    "Validating TIFF signature and SHA-256",
                    "",
                    "",
                    dataset.selectedSeries(),
                    dataset.width(),
                    dataset.height(),
                    dataset.downsample(),
                    dataset.estimatedOutputBytes()));
            verifyTiff(partial);
            var digest = sha256(partial);
            atomicReplace(partial, output);
            repository.save(dataset.withConversion(
                    DatasetStatus.CONVERSION_READY,
                    "Validated RGB OME-BigTIFF ready; original source preserved",
                    output.toString(),
                    digest,
                    dataset.selectedSeries(),
                    dataset.width(),
                    dataset.height(),
                    dataset.downsample(),
                    dataset.estimatedOutputBytes()));
        } catch (Exception error) {
            try {
                Files.deleteIfExists(partial);
                repository.save(dataset.withConversion(
                        DatasetStatus.FAILED,
                        concise(error.getMessage()),
                        "",
                        "",
                        dataset.selectedSeries(),
                        dataset.width(),
                        dataset.height(),
                        dataset.downsample(),
                        dataset.estimatedOutputBytes()));
            } catch (IOException ignored) {
                // The original conversion error remains the useful diagnostic.
            }
        }
    }

    private LocalDataset requireDataset(String id) {
        return repository.find(id)
                .orElseThrow(() -> new IllegalArgumentException("Dataset was not found"));
    }

    private Path managedDirectory(String id) {
        var directory = managedRoot.resolve(id).normalize();
        if (!directory.startsWith(managedRoot)) {
            throw new IllegalArgumentException("Dataset identifier escapes the managed root");
        }
        return directory;
    }

    private static void verifyTiff(Path path) throws IOException {
        var signature = new byte[4];
        try (InputStream input = Files.newInputStream(path)) {
            if (input.read(signature) != 4) {
                throw new IOException("Converted OME-TIFF is too short");
            }
        }
        var byteOrder = (signature[0] == 'I' && signature[1] == 'I')
                || (signature[0] == 'M' && signature[1] == 'M');
        if (!byteOrder) {
            throw new IOException("Converted output has an invalid TIFF signature");
        }
    }

    private static String sha256(Path path) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                var buffer = new byte[1024 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static void atomicReplace(Path partial, Path output) throws IOException {
        try {
            Files.move(
                    partial,
                    output,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(partial, output, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String concise(String value) {
        if (value == null || value.isBlank()) {
            return "Conversion failed";
        }
        var normalized = value.strip();
        return normalized.length() <= 600 ? normalized : normalized.substring(0, 600);
    }

    @Override
    public void close() {
        conversionExecutor.shutdownNow();
    }
}
