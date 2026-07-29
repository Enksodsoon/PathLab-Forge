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
import java.util.concurrent.Future;
import org.pathlab.forge.library.DatasetRepository;
import org.pathlab.forge.library.DatasetStatus;
import org.pathlab.forge.library.LocalDataset;
import org.pathlab.forge.derivative.DerivativeEngine;
import org.pathlab.forge.packageformat.PreparedPackageBuilder;

public final class ConversionService implements AutoCloseable {
    private final DatasetRepository repository;
    private final ConversionEngine engine;
    private final DerivativeEngine derivativeEngine;
    private final Path managedRoot;
    private final Map<String, List<SeriesInfo>> inspectedSeries = new ConcurrentHashMap<>();
    private final Map<String, Future<?>> activeConversions = new ConcurrentHashMap<>();
    private final java.util.Set<String> cancelled =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final ExecutorService conversionExecutor = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "pathlab-forge-conversion");
        thread.setDaemon(true);
        return thread;
    });

    public ConversionService(
            DatasetRepository repository,
            ConversionEngine engine,
            DerivativeEngine derivativeEngine,
            Path managedRoot) {
        this.repository = repository;
        this.engine = engine;
        this.derivativeEngine = derivativeEngine;
        this.managedRoot = managedRoot.toAbsolutePath().normalize();
    }

    public ConversionEngine engine() {
        return engine;
    }

    public DerivativeEngine derivativeEngine() {
        return derivativeEngine;
    }

    public LocalArtifacts artifacts(String id) {
        requireDataset(id);
        var directory = managedDirectory(id);
        var derivative = directory.resolve("derivative");
        return new LocalArtifacts(
                directory.resolve("export.ome.btf"),
                derivative,
                derivative.resolve("slide.dzi"),
                derivative.resolve("thumbnail.jpg"),
                directory.resolve("slide.plslide"));
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
            var ready = inspecting.withExportConfiguration(
                    DatasetStatus.READY_TO_CONVERT,
                    series.size() + " image series found; select a series and export",
                    selected.index(),
                    selected.width(),
                    selected.height(),
                    1,
                    OutputSizeEstimator.rgbPyramidUpperBound(
                            selected.width(), selected.height(), 1),
                    0,
                    0,
                    selected.width(),
                    selected.height());
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
        var info = requireSeriesInfo(id, seriesIndex);
        return selectSeries(
                id, seriesIndex, downsample, 0, 0, info.width(), info.height());
    }

    public LocalDataset selectSeries(
            String id,
            int seriesIndex,
            int downsample,
            int cropX,
            int cropY,
            int cropWidth,
            int cropHeight)
            throws IOException {
        var dataset = requireDataset(id);
        var info = requireSeriesInfo(id, seriesIndex);
        if (!info.isRgbPlane()) {
            throw new IllegalArgumentException(
                    "Select a 2D RGB series (3 channels, one Z plane, one timepoint)");
        }
        var request = new ConversionRequest(
                Path.of(dataset.sourcePath()),
                seriesIndex,
                cropX,
                cropY,
                cropWidth,
                cropHeight,
                info.width(),
                info.height(),
                downsample);
        var updated = dataset.withExportConfiguration(
                DatasetStatus.READY_TO_CONVERT,
                "Series " + info.index() + " · "
                        + request.outputWidth() + " × " + request.outputHeight()
                        + " RGB export at " + downsample + "x",
                info.index(),
                info.width(),
                info.height(),
                downsample,
                request.estimatedRgbPyramidBytes(),
                cropX,
                cropY,
                cropWidth,
                cropHeight);
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
                dataset.outputPath(),
                dataset.sha256(),
                dataset.selectedSeries(),
                dataset.width(),
                dataset.height(),
                dataset.downsample(),
                dataset.estimatedOutputBytes());
        repository.save(converting);
        cancelled.remove(id);
        activeConversions.put(id, conversionExecutor.submit(() -> convert(converting)));
        return converting;
    }

    public LocalDataset cancel(String id) throws IOException {
        var dataset = requireDataset(id);
        cancelled.add(id);
        var future = activeConversions.remove(id);
        if (future != null) {
            future.cancel(true);
        }
        var cancelledDataset = dataset.withPreparation(
                DatasetStatus.CANCELLED,
                "Conversion cancelled; completed outputs were preserved",
                dataset.outputPath(),
                dataset.sha256());
        repository.save(cancelledDataset);
        return cancelledDataset;
    }

    private void convert(LocalDataset dataset) {
        var outputDirectory = managedDirectory(dataset.id());
        var output = outputDirectory.resolve("export.ome.btf");
        var partial = output.resolveSibling("export.partial.ome.btf");
        var rendered = output.resolveSibling("render.partial.ome.btf");
        var derivativePartial = outputDirectory.resolve("derivative.partial");
        var derivative = outputDirectory.resolve("derivative");
        try {
            Files.createDirectories(outputDirectory);
            Files.deleteIfExists(partial);
            Files.deleteIfExists(rendered);
            var request = new ConversionRequest(
                    Path.of(dataset.sourcePath()),
                    dataset.selectedSeries(),
                    dataset.cropX(),
                    dataset.cropY(),
                    dataset.cropWidth(),
                    dataset.cropHeight(),
                    dataset.width(),
                    dataset.height(),
                    dataset.downsample());
            engine.convert(request, rendered);
            if (derivativeEngine.available()) {
                derivativeEngine.optimizeOme(rendered, partial);
                Files.deleteIfExists(rendered);
            } else {
                Files.move(rendered, partial, StandardCopyOption.REPLACE_EXISTING);
            }
            repository.save(dataset.withConversion(
                    DatasetStatus.VALIDATING,
                    "Validating TIFF signature and SHA-256",
                    dataset.outputPath(),
                    dataset.sha256(),
                    dataset.selectedSeries(),
                    dataset.width(),
                    dataset.height(),
                    dataset.downsample(),
                    dataset.estimatedOutputBytes()));
            verifyTiff(partial);
            var digest = sha256(partial);
            atomicReplace(partial, output);
            if (!derivativeEngine.available()) {
                repository.save(dataset.withConversion(
                        DatasetStatus.CONVERSION_READY,
                        "Validated RGB OME-BigTIFF ready; libvips is required for DZI",
                        output.toString(),
                        digest,
                        dataset.selectedSeries(),
                        dataset.width(),
                        dataset.height(),
                        dataset.downsample(),
                        dataset.estimatedOutputBytes()));
                return;
            }
            repository.save(dataset.withConversion(
                    DatasetStatus.GENERATING_DZI,
                    "Generating optimized Viewer-compatible DZI and thumbnail",
                    output.toString(),
                    digest,
                    dataset.selectedSeries(),
                    dataset.width(),
                    dataset.height(),
                    dataset.downsample(),
                    dataset.estimatedOutputBytes()));
            deleteTree(outputDirectory, derivativePartial);
            var derivativeInfo = derivativeEngine.generateDzi(
                    output,
                    derivativePartial,
                    request.outputWidth(),
                    request.outputHeight());
            installDirectory(outputDirectory, derivativePartial, derivative);
            repository.save(dataset.withConversion(
                    DatasetStatus.DZI_READY,
                    derivativeInfo.tileCount() + " validated DZI tiles; building upload package",
                    output.toString(),
                    digest,
                    dataset.selectedSeries(),
                    dataset.width(),
                    dataset.height(),
                    dataset.downsample(),
                    dataset.estimatedOutputBytes()));
            var packageInfo = PreparedPackageBuilder.build(
                    derivative,
                    request.outputWidth(),
                    request.outputHeight(),
                    outputDirectory.resolve("slide.plslide"));
            repository.save(dataset.withConversion(
                    DatasetStatus.PACKAGE_READY,
                    derivativeInfo.tileCount() + " DZI tiles · "
                            + derivativeInfo.fileCount() + " files · "
                            + derivativeInfo.bytes() + " derivative bytes · "
                            + packageInfo.bytes() + " package bytes; viewer and upload package ready",
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
                Files.deleteIfExists(rendered);
                deleteTree(outputDirectory, derivativePartial);
                if (cancelled.remove(dataset.id())) {
                    repository.save(dataset.withPreparation(
                            DatasetStatus.CANCELLED,
                            "Conversion cancelled; incomplete output removed",
                            dataset.outputPath(),
                            dataset.sha256()));
                } else {
                    repository.save(dataset.withConversion(
                            DatasetStatus.FAILED,
                            concise(error.getMessage()),
                            dataset.outputPath(),
                            dataset.sha256(),
                            dataset.selectedSeries(),
                            dataset.width(),
                            dataset.height(),
                            dataset.downsample(),
                            dataset.estimatedOutputBytes()));
                }
            } catch (IOException ignored) {
                // The original conversion error remains the useful diagnostic.
            }
        } finally {
            activeConversions.remove(dataset.id());
        }
    }

    private SeriesInfo requireSeriesInfo(String id, int seriesIndex) {
        return series(id).stream()
                .filter(item -> item.index() == seriesIndex)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Inspect this dataset before selecting a series"));
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

    private static void installDirectory(Path root, Path staging, Path destination)
            throws IOException {
        var previous = root.resolve("derivative.previous");
        deleteTree(root, previous);
        if (Files.exists(destination)) {
            Files.move(destination, previous, StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
            deleteTree(root, previous);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(staging, destination, StandardCopyOption.REPLACE_EXISTING);
            deleteTree(root, previous);
        } catch (IOException error) {
            if (!Files.exists(destination) && Files.exists(previous)) {
                Files.move(previous, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            throw error;
        }
    }

    private static void deleteTree(Path root, Path target) throws IOException {
        var normalizedRoot = root.toAbsolutePath().normalize();
        var normalizedTarget = target.toAbsolutePath().normalize();
        if (normalizedTarget.equals(normalizedRoot) || !normalizedTarget.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("Cleanup target escapes the managed dataset");
        }
        if (!Files.exists(normalizedTarget, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(normalizedTarget)) {
            for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
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
