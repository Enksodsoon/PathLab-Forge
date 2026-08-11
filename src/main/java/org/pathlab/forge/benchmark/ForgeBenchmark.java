package org.pathlab.forge.benchmark;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.pathlab.forge.conversion.BioFormatsEngine;
import org.pathlab.forge.conversion.ArtifactRevisionFormat;
import org.pathlab.forge.conversion.ConversionService;
import org.pathlab.forge.conversion.SeriesInfo;
import org.pathlab.forge.derivative.VipsRuntime;
import org.pathlab.forge.library.DatasetInspector;
import org.pathlab.forge.library.DatasetInspectionException;
import org.pathlab.forge.library.DatasetRepository;
import org.pathlab.forge.library.DatasetStatus;
import org.pathlab.forge.library.ForgePaths;
import org.pathlab.forge.runtime.ProcessTreeMemory;

public final class ForgeBenchmark {
    public static final long PACKAGE_READY_LIMIT_MS = 72_000;
    public static final long PROCESS_TREE_OBSERVED_LIMIT_BYTES = 4L * 1024 * 1024 * 1024;
    public static final long RETAINED_LIMIT_BYTES = 400L * 1024 * 1024;
    public static final long WORKSPACE_LIMIT_BYTES = 3_500L * 1024 * 1024;

    private ForgeBenchmark() {}

    public static PerformanceReport run(
            Path source, ForgePaths paths, DatasetRepository repository, Path reportPath)
            throws IOException {
        var startedAt = System.currentTimeMillis();
        long peakProcessTree = ProcessTreeMemory.workingSetBytes();
        long peakWorkspace = directoryBytes(paths.managedRoot());
        String status = "FAILED";
        String failure = "";
        long retained = 0;
        var stageDurations = new LinkedHashMap<String, Long>();
        try {
            var inspected = new DatasetInspector().inspect(source);
            var dataset = repository.find(inspected.id())
                    .filter(existing -> existing.sourceFingerprint()
                            .equals(inspected.sourceFingerprint()))
                    .orElse(inspected);
            if (dataset == inspected) {
                repository.save(dataset);
            }
            var bioFormats = BioFormatsEngine.discover(paths.dataRoot());
            var vips = VipsRuntime.discover(paths.dataRoot());
            try (var conversion =
                    new ConversionService(repository, bioFormats, vips, paths.managedRoot())) {
                var series = conversion.inspect(dataset.id());
                var configuredSeries =
                        Integer.getInteger("pathlab.forge.benchmark.series", -1);
                var selected = series.stream()
                        .filter(SeriesInfo::isRgbPlane)
                        .filter(item -> configuredSeries < 0
                                || item.index() == configuredSeries)
                        .max(Comparator.comparingLong(
                                item -> (long) item.width() * item.height()))
                        .orElseThrow(() -> new IOException("No 2D RGB image series was found"));
                var downsample =
                        Double.parseDouble(System.getProperty("pathlab.forge.benchmark.downsample", "1.0"));
                var crop = benchmarkCrop(selected.width(), selected.height());
                conversion.selectSeries(
                        dataset.id(),
                        selected.index(),
                        downsample,
                        crop[0],
                        crop[1],
                        crop[2],
                        crop[3]);
                conversion.start(dataset.id(), benchmarkFormat());
                String observedStage = "";
                long observedStageStartedAt = 0;
                while (true) {
                    var current = repository.find(dataset.id())
                            .orElseThrow(() -> new IOException("Benchmark dataset disappeared"));
                    var conversionProgress = conversion.progress(dataset.id());
                    if (!conversionProgress.stage().isBlank()
                            && !conversionProgress.stage().equals(observedStage)) {
                        if (!observedStage.isBlank() && observedStageStartedAt > 0) {
                            stageDurations.merge(
                                    observedStage,
                                    Math.max(
                                            0,
                                            conversionProgress.stageStartedAt()
                                                    - observedStageStartedAt),
                                    Math::addExact);
                        }
                        observedStage = conversionProgress.stage();
                        observedStageStartedAt = conversionProgress.stageStartedAt();
                    }
                    peakProcessTree = Math.max(
                            peakProcessTree, ProcessTreeMemory.workingSetBytes());
                    peakWorkspace = Math.max(
                            peakWorkspace, directoryBytes(paths.managedRoot()));
                    if (current.status() == DatasetStatus.PACKAGE_READY) {
                        status = "PACKAGE_READY";
                        retained = retainedBytes(conversion, current.id());
                        recordFinalStage(
                                stageDurations,
                                observedStage,
                                observedStageStartedAt,
                                System.currentTimeMillis());
                        break;
                    }
                    if (current.status() == DatasetStatus.FAILED) {
                        failure = current.detail();
                        recordFinalStage(
                                stageDurations,
                                observedStage,
                                observedStageStartedAt,
                                System.currentTimeMillis());
                        break;
                    }
                    if (System.currentTimeMillis() - startedAt
                            > Long.getLong(
                                    "pathlab.forge.benchmark.timeout.ms",
                                    TimeUnit.HOURS.toMillis(24))) {
                        failure = "BENCHMARK_TIMEOUT";
                        conversion.cancel(current.id());
                        break;
                    }
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Benchmark interrupted", error);
                    }
                }
            }
        } catch (IOException | DatasetInspectionException | RuntimeException error) {
            failure = error.getClass().getSimpleName() + ": "
                    + (error.getMessage() == null ? "" : error.getMessage());
        }
        var elapsed = System.currentTimeMillis() - startedAt;
        var passed = status.equals("PACKAGE_READY")
                && elapsed <= PACKAGE_READY_LIMIT_MS
                && peakProcessTree <= PROCESS_TREE_OBSERVED_LIMIT_BYTES
                && retained <= RETAINED_LIMIT_BYTES
                && peakWorkspace <= WORKSPACE_LIMIT_BYTES;
        if (!passed && failure.isBlank()) {
            failure = gateFailure(elapsed, peakProcessTree, retained, peakWorkspace);
        }
        var report = new PerformanceReport(
                source.toAbsolutePath().normalize().toString(),
                status,
                elapsed,
                peakProcessTree,
                retained,
                peakWorkspace,
                passed,
                failure,
                stageDurations);
        report.write(reportPath);
        return report;
    }

    static ArtifactRevisionFormat benchmarkFormat() {
        var configured = System.getProperty(
                "pathlab.forge.benchmark.format", ArtifactRevisionFormat.OME_DYNAMIC_V1.name());
        try {
            var format = ArtifactRevisionFormat.valueOf(
                    configured.trim().toUpperCase(java.util.Locale.ROOT));
            if (format != ArtifactRevisionFormat.OME_DYNAMIC_V1) {
                throw new IllegalArgumentException("Alternative conversion route is disabled");
            }
            return format;
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(
                    "pathlab.forge.benchmark.format must be OME_DYNAMIC_V1",
                    error);
        }
    }

    private static void recordFinalStage(
            java.util.Map<String, Long> stageDurations,
            String stage,
            long stageStartedAt,
            long completedAt) {
        if (!stage.isBlank() && stageStartedAt > 0) {
            stageDurations.merge(
                    stage,
                    Math.max(0, completedAt - stageStartedAt),
                    Math::addExact);
        }
    }

    private static String gateFailure(
            long elapsed,
            long peakProcessTree,
            long retained,
            long peakWorkspace) {
        if (elapsed > PACKAGE_READY_LIMIT_MS) {
            return "PACKAGE_READY_TIME_LIMIT";
        }
        if (peakProcessTree > PROCESS_TREE_OBSERVED_LIMIT_BYTES) {
            return "PROCESS_TREE_MEMORY_LIMIT";
        }
        if (retained > RETAINED_LIMIT_BYTES) {
            return "RETAINED_ARTIFACT_LIMIT";
        }
        if (peakWorkspace > WORKSPACE_LIMIT_BYTES) {
            return "PEAK_WORKSPACE_LIMIT";
        }
        return "PIPELINE_NOT_READY";
    }

    private static long retainedBytes(ConversionService conversion, String datasetId)
            throws IOException {
        var artifacts = conversion.artifacts(datasetId);
        return fileBytes(artifacts.omeTiff())
                + fileBytes(artifacts.packagePath())
                + fileBytes(artifacts.packagePath().resolveSibling(
                        artifacts.packagePath().getFileName() + ".index"));
    }

    static int[] benchmarkCrop(int width, int height) {
        var configured = System.getProperty("pathlab.forge.benchmark.crop", "").trim();
        if (configured.isEmpty()) {
            return new int[] {0, 0, width, height};
        }
        var parts = configured.split(",", -1);
        if (parts.length != 4) {
            throw new IllegalArgumentException(
                    "pathlab.forge.benchmark.crop must be x,y,width,height");
        }
        var result = new int[4];
        for (var index = 0; index < result.length; index++) {
            result[index] = Integer.parseInt(parts[index].trim());
        }
        if (result[0] < 0
                || result[1] < 0
                || result[2] <= 0
                || result[3] <= 0
                || (long) result[0] + result[2] > width
                || (long) result[1] + result[3] > height) {
            throw new IllegalArgumentException("Benchmark crop is outside the selected series");
        }
        return result;
    }

    private static long fileBytes(Path file) throws IOException {
        return Files.isRegularFile(file) ? Files.size(file) : 0;
    }

    private static long directoryBytes(Path root) throws IOException {
        if (!Files.exists(root)) {
            return 0;
        }
        var bytes = new AtomicLong();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (attributes.isRegularFile()) {
                    bytes.addAndGet(attributes.size());
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException error) {
                return FileVisitResult.CONTINUE;
            }
        });
        return bytes.get();
    }
}
