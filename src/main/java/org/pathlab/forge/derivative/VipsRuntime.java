package org.pathlab.forge.derivative;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.pathlab.forge.conversion.ConversionRequest;

public final class VipsRuntime implements DerivativeEngine {
    private static final Duration OPERATION_TIMEOUT = Duration.ofHours(24);
    private final Path executable;

    private VipsRuntime(Path executable) {
        this.executable = executable;
    }

    public static VipsRuntime discover(Path dataRoot) {
        var candidates = new ArrayList<Path>();
        var configured = System.getProperty("pathlab.forge.vips");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("PATHLAB_FORGE_VIPS");
        }
        if (configured != null && !configured.isBlank()) {
            candidates.add(Path.of(configured));
        }
        candidates.add(dataRoot.resolve("runtime").resolve("vips"));
        candidates.add(Path.of(
                System.getProperty("user.home"),
                ".cache",
                "pathlab-libvips-8.18.2",
                "extract",
                "vips-dev-8.18",
                "bin"));
        for (var candidate : candidates) {
            var found = findExecutable(candidate);
            if (found != null) {
                return new VipsRuntime(found);
            }
        }
        return new VipsRuntime(null);
    }

    @Override
    public boolean available() {
        return executable != null;
    }

    @Override
    public String description() {
        return available() ? "libvips 8.18 local runtime" : "libvips runtime not installed";
    }

    @Override
    public boolean supportsOmeRendering() {
        return available();
    }

    @Override
    public void renderOme(ConversionRequest request, Path output) throws IOException {
        requireAvailable();
        var cropped = output.resolveSibling("crop.partial.v");
        Files.deleteIfExists(cropped);
        try {
            run(List.of(
                    "crop",
                    request.source().toString(),
                    cropped.toString(),
                    Integer.toString(request.cropX()),
                    Integer.toString(request.cropY()),
                    Integer.toString(request.cropWidth()),
                    Integer.toString(request.cropHeight())));
            run(List.of(
                    "thumbnail",
                    cropped.toString(),
                    output.toString(),
                    Integer.toString(request.outputWidth()),
                    "--height",
                    Integer.toString(request.outputHeight()),
                    "--size",
                    "force"));
            requireNonempty(output, "rendered OME-TIFF");
        } finally {
            Files.deleteIfExists(cropped);
        }
    }

    @Override
    public void assembleRegions(List<Path> regions, Path renderedOme) throws IOException {
        requireAvailable();
        if (regions.size() < 2) {
            throw new IllegalArgumentException("At least two rendered regions are required");
        }
        for (var region : regions) {
            requireNonempty(region, "rendered RGB region");
        }
        run(List.of(
                "arrayjoin",
                serializeImageArray(regions),
                renderedOme + "[tile,tile-width=512,tile-height=512,"
                        + "compression=jpeg,Q=95,bigtiff]",
                "--across",
                "1"));
        requireNonempty(renderedOme, "assembled rendered OME-TIFF");
    }

    @Override
    public boolean supportsDirectFinalOme() {
        return available();
    }

    @Override
    public void validateOmeGeometry(Path omeTiff, int width, int height) throws IOException {
        var header = executable.resolveSibling(
                java.io.File.separatorChar == '\\' ? "vipsheader.exe" : "vipsheader");
        var actualWidth = parseIntegerOutput(runCommand(
                List.of(header.toString(), "-f", "width", omeTiff.toString())));
        var actualHeight = parseIntegerOutput(runCommand(
                List.of(header.toString(), "-f", "height", omeTiff.toString())));
        if (actualWidth != width || actualHeight != height) {
            throw new IOException(
                    "OME geometry mismatch: expected "
                            + width + "x" + height
                            + " but found " + actualWidth + "x" + actualHeight);
        }
    }

    @Override
    public void assembleRegionsFinal(
            List<Path> regions, Path pyramidalOme, int width, int height) throws IOException {
        assembleRegionsFinal(regions, pyramidalOme, width, height, 1.0);
    }

    @Override
    public void assembleRegionsFinal(
            List<Path> regions,
            Path pyramidalOme,
            int width,
            int height,
            double downsample)
            throws IOException {
        requireAvailable();
        if (regions.size() < 2 || width < 1 || height < 1) {
            throw new IllegalArgumentException("Direct final OME geometry is invalid");
        }
        for (var region : regions) {
            requireNonempty(region, "rendered RGB region");
        }
        var prepared = regions;
        var resizedRoot = pyramidalOme.resolveSibling("resized-regions.partial");
        var paddedJoin = pyramidalOme.resolveSibling("joined-resized.partial.tif");
        try {
            Files.deleteIfExists(paddedJoin);
            boolean uniformTargetHeights = true;
            if (downsample != 1.0) {
                deleteTree(resizedRoot);
                Files.createDirectories(resizedRoot);
                var resized = new ArrayList<Path>(regions.size());
                var sourceHeights = new ArrayList<Integer>(regions.size());
                for (var region : regions) {
                    sourceHeights.add(imageDimension(region, "height"));
                }
                var targetHeights = targetRegionHeights(sourceHeights, height);
                uniformTargetHeights = targetHeights.stream().distinct().count() == 1;
                for (var index = 0; index < regions.size(); index++) {
                    var targetHeight = targetHeights.get(index);
                    var output = resizedRoot.resolve("region-%02d.tif".formatted(index));
                    run(List.of(
                            "thumbnail",
                            regions.get(index).toString(),
                            output + "[tile,tile-width=512,tile-height=512,"
                                    + "compression=jpeg,Q=95,bigtiff,properties=false]",
                            Integer.toString(width),
                            "--height",
                            Integer.toString(targetHeight),
                            "--size",
                            "force"));
                    resized.add(output);
                }
                prepared = List.copyOf(resized);
            }
            if (uniformTargetHeights) {
                run(List.of(
                        "arrayjoin",
                        serializeImageArray(prepared),
                        pyramidalOme + "[pyramid,tile,tile-width=512,tile-height=512,"
                                + "compression=jpeg,Q=" + omeJpegQuality(width, height)
                                + ",bigtiff,subifd]",
                        "--across",
                        "1"));
            } else {
                run(List.of(
                        "arrayjoin",
                        serializeImageArray(prepared),
                        paddedJoin + "[tile,tile-width=512,tile-height=512,"
                                + "compression=jpeg,Q=95,bigtiff]",
                        "--across",
                        "1"));
                run(List.of(
                        "crop",
                        paddedJoin.toString(),
                        pyramidalOme + "[pyramid,tile,tile-width=512,tile-height=512,"
                                + "compression=jpeg,Q=" + omeJpegQuality(width, height)
                                + ",bigtiff,subifd]",
                        "0",
                        "0",
                        Integer.toString(width),
                        Integer.toString(height)));
            }
            requireNonempty(pyramidalOme, "final pyramidal OME-TIFF");
        } finally {
            Files.deleteIfExists(paddedJoin);
            deleteTree(resizedRoot);
        }
    }

    static List<Integer> targetRegionHeights(List<Integer> sourceHeights, int targetHeight) {
        if (sourceHeights.isEmpty()
                || targetHeight < sourceHeights.size()
                || sourceHeights.stream().anyMatch(height -> height == null || height < 1)) {
            throw new IllegalArgumentException("Region resample geometry is invalid");
        }
        var totalSourceHeight = sourceHeights.stream()
                .mapToLong(Integer::longValue)
                .reduce(0, Math::addExact);
        var targetHeights = new ArrayList<Integer>(sourceHeights.size());
        long cumulativeSourceHeight = 0;
        int previousTargetBottom = 0;
        for (var index = 0; index < sourceHeights.size(); index++) {
            cumulativeSourceHeight = Math.addExact(
                    cumulativeSourceHeight, sourceHeights.get(index));
            var targetBottom = index == sourceHeights.size() - 1
                    ? targetHeight
                    : Math.toIntExact(Math.round(
                            (double) cumulativeSourceHeight * targetHeight
                                    / totalSourceHeight));
            var regionHeight = targetBottom - previousTargetBottom;
            if (regionHeight < 1) {
                throw new IllegalArgumentException("Resampled region height is invalid");
            }
            targetHeights.add(regionHeight);
            previousTargetBottom = targetBottom;
        }
        return List.copyOf(targetHeights);
    }

    private int imageDimension(Path image, String field) throws IOException {
        var header = executable.resolveSibling(
                java.io.File.separatorChar == '\\' ? "vipsheader.exe" : "vipsheader");
        return parseIntegerOutput(runCommand(
                List.of(header.toString(), "-f", field, image.toString())));
    }

    static int parseIntegerOutput(String output) throws IOException {
        var lines = output.lines().toList();
        for (var index = lines.size() - 1; index >= 0; index--) {
            var value = lines.get(index).strip();
            if (value.matches("[0-9]+")) {
                try {
                    return Integer.parseInt(value);
                } catch (NumberFormatException error) {
                    throw new IOException("libvips numeric output is outside the supported range", error);
                }
            }
        }
        throw new IOException("libvips did not return a numeric image property: " + tail(output));
    }

    static String serializeImageArray(List<Path> paths) {
        if (paths.isEmpty()) {
            throw new IllegalArgumentException("Image array is empty");
        }
        return paths.stream()
                .map(path -> path.toAbsolutePath().normalize().toString().replace('\\', '/'))
                .map(path -> path.replace(" ", "\\ "))
                .collect(java.util.stream.Collectors.joining(" "));
    }

    @Override
    public void optimizeOme(Path renderedOme, Path pyramidalOme, int width, int height)
            throws IOException {
        requireAvailable();
        var jpegQuality = omeJpegQuality(width, height);
        run(List.of(
                "thumbnail",
                renderedOme.toString(),
                pyramidalOme + "[pyramid,tile,tile-width=512,tile-height=512,"
                        + "compression=jpeg,Q=" + jpegQuality + ",bigtiff,subifd]",
                Integer.toString(width),
                "--height",
                Integer.toString(height),
                "--size",
                "force"));
        requireNonempty(pyramidalOme, "pyramidal OME-TIFF");
    }

    static int omeJpegQuality(int width, int height) {
        var configured = System.getProperty("pathlab.forge.ome.jpegQuality");
        var quality = configured == null
                ? (long) width * height >= 1_000_000_000L ? 75 : 93
                : Integer.parseInt(configured);
        if (!List.of(75, 80, 85, 90, 93).contains(quality)) {
            throw new IllegalArgumentException(
                    "OME JPEG quality must be one of 75, 80, 85, 90 or 93");
        }
        return quality;
    }

    @Override
    public DerivativeInfo generateDzi(
            Path omeTiff, Path outputRoot, int width, int height) throws IOException {
        return generateDzi(omeTiff, outputRoot, width, height, ignored -> {});
    }

    @Override
    public DerivativeInfo generateViewerDzi(
            Path omeTiff, Path outputRoot, int width, int height) throws IOException {
        requireAvailable();
        Files.createDirectories(outputRoot);
        run(List.of(
                "dzsave",
                omeTiff.toString(),
                outputRoot.resolve("slide").toString(),
                "--layout",
                "dz",
                "--tile-size",
                "512",
                "--overlap",
                "1",
                "--suffix",
                compactJpegSuffix(85),
                "--depth",
                "onepixel",
                "--region-shrink",
                "mean",
                "--skip-blanks",
                "-1"));
        run(List.of(
                "thumbnail",
                omeTiff.toString(),
                outputRoot.resolve("thumbnail.jpg[Q=82,strip]").toString(),
                "640",
                "--size",
                "down"));
        Files.deleteIfExists(outputRoot.resolve("slide_files").resolve("vips-properties.xml"));
        var expectedTiles = DziValidator.expectedTileCount(width, height);
        int tileCount;
        int fileCount;
        long bytes;
        try (var paths = Files.walk(outputRoot)) {
            var files = paths.filter(Files::isRegularFile).toList();
            fileCount = files.size();
            tileCount = (int) files.stream()
                    .filter(path -> path.getFileName().toString().endsWith(".jpg"))
                    .filter(path -> !path.getFileName().toString().equals("thumbnail.jpg"))
                    .count();
            bytes = 0;
            for (var file : files) {
                bytes = Math.addExact(bytes, Files.size(file));
            }
        }
        if (!Files.isRegularFile(outputRoot.resolve("slide.dzi")) || tileCount != expectedTiles) {
            throw new IOException("Viewer pyramid is incomplete: expected "
                    + expectedTiles + " tiles but found " + tileCount);
        }
        return new DerivativeInfo(
                outputRoot,
                bytes,
                fileCount,
                tileCount,
                "viewer-cache",
                java.util.List.of(),
                85,
                1.0,
                0.0,
                1.0,
                "viewer-cache-420-optimized");
    }

    @Override
    public void generateViewerThumbnail(
            Path source, int seriesIndex, Path output, int maxDimension) throws IOException {
        requireAvailable();
        if (seriesIndex < 0 || maxDimension < 96 || maxDimension > 1024) {
            throw new IllegalArgumentException("Viewer thumbnail request is invalid");
        }
        Files.createDirectories(output.toAbsolutePath().normalize().getParent());
        run(List.of(
                "thumbnail",
                source + "[page=" + seriesIndex + "]",
                output + "[Q=82,strip]",
                Integer.toString(maxDimension),
                "--size",
                "down"));
    }

    @Override
    public DerivativeInfo generateDzi(
            Path omeTiff,
            Path outputRoot,
            int width,
            int height,
            java.util.function.Consumer<DerivativeProgress> progress)
            throws IOException {
        requireAvailable();
        Files.createDirectories(outputRoot);
        var selection = selectDziQuality(omeTiff, outputRoot, width, height, progress);
        var expectedTiles = DziValidator.expectedTileCount(width, height);
        progress.accept(new DerivativeProgress("DZI_TILES", 0, expectedTiles));
        runWithProgress(List.of(
                "dzsave",
                omeTiff.toString(),
                outputRoot.resolve("slide").toString(),
                "--layout",
                "dz",
                "--tile-size",
                "512",
                "--overlap",
                "1",
                "--suffix",
                jpegSuffix(selection.quality(), selection.encoderProfile()),
                "--depth",
                "onepixel",
                "--region-shrink",
                "mean",
                "--skip-blanks",
                "-1"),
                percent -> progress.accept(new DerivativeProgress(
                        "DZI_TILES",
                        Math.min(expectedTiles, Math.round(expectedTiles * percent / 100.0)),
                        expectedTiles)));
        run(List.of(
                "thumbnail",
                omeTiff.toString(),
                outputRoot.resolve("thumbnail.jpg[Q=82,strip]").toString(),
                "640",
                "--size",
                "down"));
        Files.deleteIfExists(outputRoot.resolve("slide_files").resolve("vips-properties.xml"));
        var validationUnits = Math.addExact(Math.multiplyExact(expectedTiles, 2), 2);
        progress.accept(new DerivativeProgress("DZI_VALIDATING", 0, validationUnits));
        var validated = DziValidator.validate(
                outputRoot,
                width,
                height,
                (completed, total) -> progress.accept(
                        new DerivativeProgress("DZI_VALIDATING", completed, total)));
        return new DerivativeInfo(
                validated.root(),
                validated.bytes(),
                validated.fileCount(),
                validated.tileCount(),
                validated.sha256(),
                validated.ledger(),
                selection.quality(),
                selection.minimumWindowedSsim(),
                selection.meanDeltaE00(),
                selection.minimumEdgeDetailRetention(),
                selection.encoderProfile());
    }

    @Override
    public boolean supportsDirectDziFromRegions() {
        return true;
    }

    @Override
    public DerivativeInfo generateDziFromRegions(
            List<Path> regions,
            Path outputRoot,
            int width,
            int height,
            double downsample,
            java.util.function.Consumer<DerivativeProgress> progress)
            throws IOException {
        requireAvailable();
        if (regions.size() < 2
                || width < 1
                || height < 1
                || !Double.isFinite(downsample)
                || downsample <= 0) {
            throw new IllegalArgumentException("Direct DZI region geometry is invalid");
        }
        for (var region : regions) {
            requireNonempty(region, "rendered RGB region");
        }
        Files.createDirectories(outputRoot);
        var preparedRoot = outputRoot.resolve("direct-regions");
        var qualityRoot = outputRoot.resolve("direct-quality");
        try {
            progress.accept(new DerivativeProgress(
                    "DIRECT_DZI_PREPARING", 0, regions.size()));
            var prepared = prepareDirectRegions(
                    regions,
                    preparedRoot,
                    width,
                    height,
                    downsample,
                    completed -> progress.accept(new DerivativeProgress(
                            "DIRECT_DZI_PREPARING", completed, regions.size())));
            var selection = selectDziQualityFromRegions(
                    prepared, qualityRoot, width, height, progress);
            var expectedTiles = DziValidator.expectedTileCount(width, height);
            progress.accept(new DerivativeProgress("DZI_TILES", 0, expectedTiles));
            runWithProgress(
                    List.of(
                            "arrayjoin",
                            serializeImageArray(prepared.paths()),
                            outputRoot.resolve("slide.dz")
                                    + directDziSaveOptions(
                                            selection.quality(),
                                            selection.encoderProfile()),
                            "--across",
                            "1"),
                    percent -> progress.accept(new DerivativeProgress(
                            "DZI_TILES",
                            Math.min(
                                    expectedTiles,
                                    Math.round(expectedTiles * percent / 100.0)),
                            expectedTiles)));
            createDirectThumbnail(prepared, outputRoot.resolve("thumbnail.jpg"));
            deleteTree(preparedRoot);
            deleteTree(qualityRoot);
            Files.deleteIfExists(
                    outputRoot.resolve("slide_files").resolve("vips-properties.xml"));
            var validationUnits = Math.addExact(Math.multiplyExact(expectedTiles, 2), 2);
            progress.accept(new DerivativeProgress(
                    "DZI_VALIDATING", 0, validationUnits));
            var validated = DziValidator.validate(
                    outputRoot,
                    width,
                    height,
                    (completed, total) -> progress.accept(
                            new DerivativeProgress("DZI_VALIDATING", completed, total)));
            return new DerivativeInfo(
                    validated.root(),
                    validated.bytes(),
                    validated.fileCount(),
                    validated.tileCount(),
                    validated.sha256(),
                    validated.ledger(),
                    selection.quality(),
                    selection.minimumWindowedSsim(),
                    selection.meanDeltaE00(),
                    selection.minimumEdgeDetailRetention(),
                    selection.encoderProfile());
        } finally {
            deleteTree(preparedRoot);
            deleteTree(qualityRoot);
            for (var quality : AdaptiveJpegQualitySelector.QUALITIES) {
                Files.deleteIfExists(
                        qualityRoot.resolve("quality-candidate-" + quality + ".jpg"));
            }
        }
    }

    private PreparedRegions prepareDirectRegions(
            List<Path> regions,
            Path preparedRoot,
            int width,
            int height,
            double downsample,
            java.util.function.IntConsumer progress)
            throws IOException {
        deleteTree(preparedRoot);
        Files.createDirectories(preparedRoot);
        var sourceHeights = new ArrayList<Integer>(regions.size());
        for (var region : regions) {
            sourceHeights.add(imageDimension(region, "height"));
        }
        if (downsample == 1.0) {
            progress.accept(regions.size());
            return new PreparedRegions(List.copyOf(regions), List.copyOf(sourceHeights));
        }
        var targetHeights = targetRegionHeights(sourceHeights, height);
        var prepared = new ArrayList<Path>(regions.size());
        for (var index = 0; index < regions.size(); index++) {
            var output = preparedRoot.resolve("region-%02d.tif".formatted(index));
            run(List.of(
                    "thumbnail",
                    regions.get(index).toString(),
                    output + "[tile,tile-width=512,tile-height=512,"
                            + "compression=jpeg,Q=95,bigtiff,properties=false]",
                    Integer.toString(width),
                    "--height",
                    Integer.toString(targetHeights.get(index)),
                    "--size",
                    "force"));
            prepared.add(output);
            progress.accept(index + 1);
        }
        if (targetHeights.stream().mapToInt(Integer::intValue).sum() != height) {
            throw new IOException("Direct DZI regions do not match the target height");
        }
        if (targetHeights.stream().distinct().count() != 1) {
            throw new IOException(
                    "Direct DZI requires uniformly aligned region heights");
        }
        return new PreparedRegions(List.copyOf(prepared), List.copyOf(targetHeights));
    }

    private AdaptiveJpegQualitySelector.Selection selectDziQualityFromRegions(
            PreparedRegions prepared,
            Path qualityRoot,
            int width,
            int height,
            java.util.function.Consumer<DerivativeProgress> progress)
            throws IOException {
        deleteTree(qualityRoot);
        Files.createDirectories(qualityRoot);
        var overviewRoot = qualityRoot.resolve("overview-regions");
        var roiRoot = qualityRoot.resolve("quality-rois");
        var overview = qualityRoot.resolve("quality-overview.png");
        var probe = qualityRoot.resolve("quality-probe.png");
        Files.createDirectories(overviewRoot);
        Files.createDirectories(roiRoot);
        progress.accept(new DerivativeProgress(
                "QUALITY_OVERVIEW", 0, prepared.paths().size()));
        var overviewRegions = new ArrayList<Path>(prepared.paths().size());
        for (var index = 0; index < prepared.paths().size(); index++) {
            var output = overviewRoot.resolve("region-%02d.png".formatted(index));
            run(List.of(
                    "thumbnail",
                    prepared.paths().get(index).toString(),
                    output.toString(),
                    "1024",
                    "--size",
                    "down"));
            overviewRegions.add(output);
            progress.accept(new DerivativeProgress(
                    "QUALITY_OVERVIEW", index + 1, prepared.paths().size()));
        }
        run(List.of(
                "arrayjoin",
                serializeImageArray(overviewRegions),
                overview.toString(),
                "--across",
                "1"));
        var planned = AdaptiveJpegQualitySelector.planNativeRois(
                overview, width, height);
        var groupedRois = new LinkedHashMap<Integer, List<AdaptiveJpegQualitySelector.Roi>>();
        var groupedOutputs = new LinkedHashMap<Integer, List<Path>>();
        var regionTops = regionTops(prepared.heights());
        for (var index = 0; index < planned.size(); index++) {
            var roi = planned.get(index);
            var regionIndex = containingRegion(
                    roi.y() + roi.height() / 2, regionTops, prepared.heights());
            var localY = Math.max(
                    0,
                    Math.min(
                            prepared.heights().get(regionIndex) - roi.height(),
                            roi.y() - regionTops.get(regionIndex)));
            groupedRois.computeIfAbsent(regionIndex, ignored -> new ArrayList<>())
                    .add(new AdaptiveJpegQualitySelector.Roi(
                            roi.x(), localY, roi.width(), roi.height()));
            groupedOutputs.computeIfAbsent(regionIndex, ignored -> new ArrayList<>())
                    .add(roiRoot.resolve("roi-%02d.png".formatted(index)));
        }
        var extracted = new java.util.concurrent.atomic.AtomicInteger();
        progress.accept(new DerivativeProgress("QUALITY_ROIS", 0, planned.size()));
        for (var entry : groupedRois.entrySet()) {
            var outputs = groupedOutputs.get(entry.getKey());
            extractQualityRois(
                    prepared.paths().get(entry.getKey()),
                    entry.getValue(),
                    outputs,
                    completed -> progress.accept(new DerivativeProgress(
                            "QUALITY_ROIS",
                            Math.min(
                                    planned.size(),
                                    extracted.get() + completed),
                            planned.size())));
            extracted.addAndGet(outputs.size());
        }
        var roiFiles = new ArrayList<Path>(planned.size());
        for (var index = 0; index < planned.size(); index++) {
            roiFiles.add(roiRoot.resolve("roi-%02d.png".formatted(index)));
        }
        run(List.of(
                "arrayjoin",
                serializeImageArray(roiFiles),
                probe.toString(),
                "--across",
                "8"));
        return selectDziQualityFromProbe(probe, qualityRoot, progress);
    }

    private void createDirectThumbnail(PreparedRegions prepared, Path output)
            throws IOException {
        var root = output.resolveSibling("thumbnail-regions.partial");
        var overview = output.resolveSibling("thumbnail-overview.partial.png");
        try {
            deleteTree(root);
            Files.createDirectories(root);
            var thumbnails = new ArrayList<Path>(prepared.paths().size());
            for (var index = 0; index < prepared.paths().size(); index++) {
                var thumbnail = root.resolve("region-%02d.png".formatted(index));
                run(List.of(
                        "thumbnail",
                        prepared.paths().get(index).toString(),
                        thumbnail.toString(),
                        "640",
                        "--size",
                        "down"));
                thumbnails.add(thumbnail);
            }
            run(List.of(
                    "arrayjoin",
                    serializeImageArray(thumbnails),
                    overview.toString(),
                    "--across",
                    "1"));
            run(List.of(
                    "thumbnail",
                    overview.toString(),
                    output + "[Q=82,strip]",
                    "640",
                    "--size",
                    "down"));
        } finally {
            Files.deleteIfExists(overview);
            deleteTree(root);
        }
    }

    private static String directDziSaveOptions(int quality, String encoderProfile) {
        return "[container=fs,layout=dz,tile-size=512,overlap=1,"
                + "depth=onepixel,region-shrink=mean,skip-blanks=-1,suffix="
                + jpegSuffix(quality, encoderProfile) + "]";
    }

    private static List<Integer> regionTops(List<Integer> heights) {
        var tops = new ArrayList<Integer>(heights.size());
        var top = 0;
        for (var height : heights) {
            tops.add(top);
            top = Math.addExact(top, height);
        }
        return List.copyOf(tops);
    }

    private static int containingRegion(
            int y, List<Integer> tops, List<Integer> heights) {
        for (var index = 0; index < heights.size(); index++) {
            if (y < tops.get(index) + heights.get(index)) {
                return index;
            }
        }
        return heights.size() - 1;
    }

    AdaptiveJpegQualitySelector.Selection selectDziQuality(
            Path omeTiff, Path outputRoot, int width, int height) throws IOException {
        return selectDziQuality(
                omeTiff, outputRoot, width, height, ignored -> {});
    }

    private AdaptiveJpegQualitySelector.Selection selectDziQuality(
            Path omeTiff,
            Path outputRoot,
            int width,
            int height,
            java.util.function.Consumer<DerivativeProgress> progress)
            throws IOException {
        requireAvailable();
        Files.createDirectories(outputRoot);
        var overview = outputRoot.resolve("quality-overview.png");
        var probe = outputRoot.resolve("quality-probe.png");
        var roiRoot = outputRoot.resolve("quality-rois");
        try {
            progress.accept(new DerivativeProgress("QUALITY_OVERVIEW", 0, 1));
            run(List.of(
                    "thumbnail",
                    omeTiff.toString(),
                    overview.toString(),
                    "1024",
                    "--size",
                    "down"));
            progress.accept(new DerivativeProgress("QUALITY_OVERVIEW", 1, 1));
            deleteTree(roiRoot);
            Files.createDirectories(roiRoot);
            var roiFiles = new ArrayList<Path>();
            var rois = AdaptiveJpegQualitySelector.planNativeRois(
                    overview, width, height);
            for (var index = 0; index < rois.size(); index++) {
                roiFiles.add(roiRoot.resolve("roi-%02d.png".formatted(index)));
            }
            extractQualityRois(
                    omeTiff,
                    rois,
                    roiFiles,
                    completed -> progress.accept(new DerivativeProgress(
                            "QUALITY_ROIS", completed, rois.size())));
            run(List.of(
                    "arrayjoin",
                    serializeImageArray(roiFiles),
                    probe.toString(),
                    "--across",
                    "8"));
            return selectDziQualityFromProbe(probe, outputRoot, progress);
        } finally {
            Files.deleteIfExists(overview);
            Files.deleteIfExists(probe);
            deleteTree(roiRoot);
            for (var quality : AdaptiveJpegQualitySelector.QUALITIES) {
                Files.deleteIfExists(
                        outputRoot.resolve("quality-candidate-" + quality + ".jpg"));
            }
        }
    }

    private AdaptiveJpegQualitySelector.Selection selectDziQualityFromProbe(
            Path probe,
            Path outputRoot,
            java.util.function.Consumer<DerivativeProgress> progress)
            throws IOException {
        if (parallelQualityProfiles()) {
            return selectDziQualityProfilesInParallel(probe, outputRoot, progress);
        }
        return selectDziQualityProfilesSequentially(probe, outputRoot, progress);
    }

    private AdaptiveJpegQualitySelector.Selection selectDziQualityProfilesSequentially(
            Path probe,
            Path outputRoot,
            java.util.function.Consumer<DerivativeProgress> progress)
            throws IOException {
        var candidateProgress = new java.util.concurrent.atomic.AtomicInteger();
        var encoderProfile = "compact-420-trellis";
        ProfileCandidate fourTwenty = null;
        IOException fourTwentyFailure = null;
        try {
            fourTwenty = evaluatedProfileIncrementally(
                    probe, outputRoot, encoderProfile, candidateProgress, progress);
        } catch (IOException enhancedFailure) {
            encoderProfile = "compact-420-optimized";
            try {
                fourTwenty = evaluatedProfileIncrementally(
                        probe,
                        outputRoot,
                        encoderProfile,
                        candidateProgress,
                        progress);
            } catch (IOException optimizedQualityFailure) {
                if (!isQualityGateFailure(optimizedQualityFailure)) {
                    optimizedQualityFailure.addSuppressed(enhancedFailure);
                    throw optimizedQualityFailure;
                }
                fourTwentyFailure = optimizedQualityFailure;
            }
        }
        encoderProfile = "compact-444-quality-rescue";
        ProfileCandidate fourFourFour;
        try {
            fourFourFour = evaluatedProfileIncrementally(
                    probe, outputRoot, encoderProfile, candidateProgress, progress);
        } catch (IOException fourFourFourFailure) {
            if (!isQualityGateFailure(fourFourFourFailure) || fourTwenty == null) {
                if (fourTwentyFailure != null) {
                    fourFourFourFailure.addSuppressed(fourTwentyFailure);
                }
                throw fourFourFourFailure;
            }
            return fourTwenty.selection();
        }
        if (fourTwenty == null) {
            return fourFourFour.selection();
        }
        return preferSmallerProfile(fourTwenty, fourFourFour).selection();
    }

    private AdaptiveJpegQualitySelector.Selection selectDziQualityProfilesInParallel(
            Path probe,
            Path outputRoot,
            java.util.function.Consumer<DerivativeProgress> progress)
            throws IOException {
        var candidateProgress = new java.util.concurrent.atomic.AtomicInteger();
        var progressLock = new Object();
        java.util.function.Consumer<DerivativeProgress> serializedProgress = item -> {
            synchronized (progressLock) {
                progress.accept(item);
            }
        };
        var executor = java.util.concurrent.Executors.newFixedThreadPool(
                2,
                runnable -> {
                    var thread = new Thread(runnable, "pathlab-quality-profile");
                    thread.setDaemon(true);
                    return thread;
                });
        try {
            var fourTwentyFuture = executor.submit(() -> evaluateFourTwentyProfile(
                    probe, outputRoot, candidateProgress, serializedProgress));
            var fourFourFourFuture = executor.submit(() -> evaluateProfileOutcome(
                    probe,
                    outputRoot,
                    "compact-444-quality-rescue",
                    candidateProgress,
                    serializedProgress));
            ProfileOutcome fourTwenty;
            ProfileOutcome fourFourFour;
            try {
                fourTwenty = fourTwentyFuture.get();
                fourFourFour = fourFourFourFuture.get();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("DZI quality selection was interrupted", error);
            } catch (java.util.concurrent.ExecutionException error) {
                var cause = error.getCause();
                if (cause instanceof IOException io) {
                    throw io;
                }
                throw new IOException("Parallel DZI quality selection failed", cause);
            }
            return chooseProfileOutcome(fourTwenty, fourFourFour);
        } finally {
            executor.shutdownNow();
        }
    }

    private ProfileOutcome evaluateFourTwentyProfile(
            Path probe,
            Path outputRoot,
            java.util.concurrent.atomic.AtomicInteger completed,
            java.util.function.Consumer<DerivativeProgress> progress) {
        try {
            return new ProfileOutcome(
                    evaluatedProfileIncrementally(
                            probe,
                            outputRoot,
                            "compact-420-trellis",
                            completed,
                            progress),
                    null);
        } catch (IOException enhancedFailure) {
            try {
                return new ProfileOutcome(
                        evaluatedProfileIncrementally(
                                probe,
                                outputRoot,
                                "compact-420-optimized",
                                completed,
                                progress),
                        null);
            } catch (IOException optimizedFailure) {
                optimizedFailure.addSuppressed(enhancedFailure);
                return new ProfileOutcome(null, optimizedFailure);
            }
        }
    }

    private ProfileOutcome evaluateProfileOutcome(
            Path probe,
            Path outputRoot,
            String encoderProfile,
            java.util.concurrent.atomic.AtomicInteger completed,
            java.util.function.Consumer<DerivativeProgress> progress) {
        try {
            return new ProfileOutcome(
                    evaluatedProfileIncrementally(
                            probe, outputRoot, encoderProfile, completed, progress),
                    null);
        } catch (IOException failure) {
            return new ProfileOutcome(null, failure);
        }
    }

    private static AdaptiveJpegQualitySelector.Selection chooseProfileOutcome(
            ProfileOutcome fourTwenty, ProfileOutcome fourFourFour) throws IOException {
        if (fourFourFour.candidate() == null) {
            if (!isQualityGateFailure(fourFourFour.failure())
                    || fourTwenty.candidate() == null) {
                if (fourTwenty.failure() != null) {
                    fourFourFour.failure().addSuppressed(fourTwenty.failure());
                }
                throw fourFourFour.failure();
            }
            return fourTwenty.candidate().selection();
        }
        if (fourTwenty.candidate() == null) {
            if (fourTwenty.failure() != null
                    && !isQualityGateFailure(fourTwenty.failure())) {
                throw fourTwenty.failure();
            }
            return fourFourFour.candidate().selection();
        }
        return preferSmallerProfile(
                        fourTwenty.candidate(), fourFourFour.candidate())
                .selection();
    }

    static boolean parallelQualityProfiles() {
        return org.pathlab.forge.runtime.RuntimeProfile.system()
                        .maxConversionWorkers()
                >= 8;
    }

    private void extractQualityRois(
            Path omeTiff,
            List<AdaptiveJpegQualitySelector.Roi> rois,
            List<Path> outputs,
            java.util.function.IntConsumer progress)
            throws IOException {
        if (rois.size() != outputs.size() || rois.isEmpty()) {
            throw new IllegalArgumentException("Quality ROI extraction plan is invalid");
        }
        if (!Boolean.getBoolean("pathlab.forge.quality.native.disabled")) {
            try {
                progress.accept(0);
                runCommand(nativeRoiCommandLine(executable, omeTiff, rois, outputs), progress);
                for (var output : outputs) {
                    requireNonempty(output, "native quality ROI");
                }
                return;
            } catch (IOException nativeFailure) {
                System.err.println(
                        "PathLab Forge: native quality ROI fast path unavailable; "
                                + "using safe subprocess fallback: "
                                + nativeFailure.getMessage());
                for (var output : outputs) {
                    Files.deleteIfExists(output);
                }
            }
        }
        extractQualityRoisFallback(omeTiff, rois, outputs, progress);
    }

    private void extractQualityRoisFallback(
            Path omeTiff,
            List<AdaptiveJpegQualitySelector.Roi> rois,
            List<Path> outputs,
            java.util.function.IntConsumer progress)
            throws IOException {
        var profile = org.pathlab.forge.runtime.RuntimeProfile.system();
        var configured = Integer.getInteger(
                "pathlab.forge.quality.workers",
                Math.min(5, profile.maxConversionWorkers()));
        var workers = Math.max(1, Math.min(
                Math.min(configured, profile.maxConversionWorkers()), rois.size()));
        var executor = java.util.concurrent.Executors.newFixedThreadPool(
                workers,
                runnable -> {
                    var thread = new Thread(runnable, "pathlab-quality-roi");
                    thread.setDaemon(true);
                    return thread;
                });
        try {
            progress.accept(0);
            var completion = new java.util.concurrent.ExecutorCompletionService<Void>(executor);
            for (var index = 0; index < rois.size(); index++) {
                var roi = rois.get(index);
                var output = outputs.get(index);
                completion.submit(() -> {
                    runCommand(probeCommandLine(
                            executable,
                            List.of(
                                    "crop",
                                    omeTiff.toString(),
                                    output.toString(),
                                    Integer.toString(roi.x()),
                                    Integer.toString(roi.y()),
                                    Integer.toString(roi.width()),
                                    Integer.toString(roi.height())),
                            workers));
                    return null;
                });
            }
            for (var completed = 1; completed <= rois.size(); completed++) {
                try {
                    completion.take().get();
                    progress.accept(completed);
                } catch (java.util.concurrent.ExecutionException error) {
                    var cause = error.getCause();
                    if (cause instanceof IOException io) {
                        throw io;
                    }
                    throw new IOException("Parallel quality ROI extraction failed", cause);
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Quality ROI extraction was interrupted", error);
        } finally {
            executor.shutdownNow();
        }
    }

    static List<String> nativeRoiCommandLine(
            Path executable,
            Path omeTiff,
            List<AdaptiveJpegQualitySelector.Roi> rois,
            List<Path> outputs) {
        if (rois.size() != outputs.size() || rois.isEmpty()) {
            throw new IllegalArgumentException("Quality ROI extraction plan is invalid");
        }
        var javaExecutable = Path.of(
                System.getProperty("java.home"),
                "bin",
                java.io.File.separatorChar == '\\' ? "java.exe" : "java");
        var command = new ArrayList<String>();
        command.add(javaExecutable.toString());
        command.add("-Xms32m");
        command.add("-Xmx192m");
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(VipsNativeRoiHelper.class.getName());
        command.add(executable.getParent().toString());
        command.add(omeTiff.toAbsolutePath().toString());
        command.add(outputs.get(0).toAbsolutePath().getParent().toString());
        command.add(Integer.toString(Math.min(
                5,
                org.pathlab.forge.runtime.RuntimeProfile.system()
                        .maxConversionWorkers())));
        for (var index = 0; index < rois.size(); index++) {
            var roi = rois.get(index);
            command.add(outputs.get(index).getFileName().toString());
            command.add(Integer.toString(roi.x()));
            command.add(Integer.toString(roi.y()));
            command.add(Integer.toString(roi.width()));
            command.add(Integer.toString(roi.height()));
        }
        return List.copyOf(command);
    }

    static List<String> probeCommandLine(
            Path executable, List<String> arguments, int parallelWorkers) {
        if (parallelWorkers < 1) {
            throw new IllegalArgumentException("Quality worker count is invalid");
        }
        var profile = org.pathlab.forge.runtime.RuntimeProfile.system();
        var cacheBytes = Math.max(
                64L * 1024 * 1024,
                Math.min(256L * 1024 * 1024, profile.vipsCacheBytes() / parallelWorkers));
        var command = new ArrayList<String>();
        command.add(executable.toString());
        command.add("--vips-concurrency=1");
        command.add("--vips-cache-max-memory=" + cacheBytes);
        command.add("--vips-cache-max-files=32");
        command.add("--vips-cache-max=32");
        command.addAll(arguments);
        return List.copyOf(command);
    }

    static String compactJpegSuffix(int quality) {
        return jpegSuffix(quality, "compact-420-trellis");
    }

    static String jpegSuffix(int quality, String encoderProfile) {
        if (!AdaptiveJpegQualitySelector.QUALITIES.contains(quality)) {
            throw new IllegalArgumentException("Unsupported compact DZI JPEG quality");
        }
        var enhanced = encoderProfile.equals("compact-420-trellis");
        var subsampling = encoderProfile.equals("compact-444-quality-rescue") ? "off" : "on";
        return ".jpg[Q=" + quality + ",subsample-mode=" + subsampling
                + ",optimize-coding=true,"
                + (enhanced ? "trellis-quant=true,overshoot-deringing=true," : "")
                + "interlace=false,strip]";
    }

    private ProfileCandidate evaluatedProfileIncrementally(
            Path probe,
            Path outputRoot,
            String encoderProfile,
            java.util.concurrent.atomic.AtomicInteger completed,
            java.util.function.Consumer<DerivativeProgress> progress)
            throws IOException {
        IOException lastQualityFailure = null;
        for (var quality : AdaptiveJpegQualitySelector.QUALITIES) {
            var candidate = outputRoot.resolve(
                    "quality-candidate-" + encoderProfile + "-" + quality + ".jpg");
            try {
                run(List.of(
                        "copy",
                        probe.toString(),
                        candidate + jpegSuffix(quality, encoderProfile).substring(4)));
                progress.accept(new DerivativeProgress(
                        "QUALITY_CANDIDATES",
                        completed.incrementAndGet(),
                        AdaptiveJpegQualitySelector.QUALITIES.size() * 3L));
                try {
                    var selection = AdaptiveJpegQualitySelector.selectCandidate(
                            probe, candidate, quality, encoderProfile);
                    return new ProfileCandidate(selection, Files.size(candidate));
                } catch (IOException qualityFailure) {
                    if (!isQualityGateFailure(qualityFailure)) {
                        throw qualityFailure;
                    }
                    lastQualityFailure = qualityFailure;
                }
            } finally {
                Files.deleteIfExists(candidate);
            }
        }
        throw lastQualityFailure == null
                ? new IOException("DZI quality candidates are unavailable")
                : lastQualityFailure;
    }

    private static boolean isQualityGateFailure(IOException error) {
        return error.getMessage() != null
                && error.getMessage().startsWith("DZI JPEG quality gate failed");
    }

    static AdaptiveJpegQualitySelector.Selection preferSmallerProfile(
            AdaptiveJpegQualitySelector.Selection first,
            long firstCandidateBytes,
            AdaptiveJpegQualitySelector.Selection second,
            long secondCandidateBytes) {
        return preferSmallerProfile(
                        new ProfileCandidate(first, firstCandidateBytes),
                        new ProfileCandidate(second, secondCandidateBytes))
                .selection();
    }

    private static ProfileCandidate preferSmallerProfile(
            ProfileCandidate first, ProfileCandidate second) {
        if (first.candidateBytes() != second.candidateBytes()) {
            return first.candidateBytes() < second.candidateBytes() ? first : second;
        }
        return first.selection().quality() <= second.selection().quality() ? first : second;
    }

    private record ProfileCandidate(
            AdaptiveJpegQualitySelector.Selection selection, long candidateBytes) {}

    private record ProfileOutcome(ProfileCandidate candidate, IOException failure) {}

    private record PreparedRegions(List<Path> paths, List<Integer> heights) {}

    private String run(List<String> arguments) throws IOException {
        var command = commandLine(executable, arguments);
        return runCommand(command);
    }

    private String runWithProgress(
            List<String> arguments, java.util.function.IntConsumer progress)
            throws IOException {
        var command = new ArrayList<>(commandLine(executable, arguments));
        command.add(5, "--vips-progress");
        return runCommand(command, progress);
    }

    private String runCommand(List<String> command) throws IOException {
        return runCommand(command, ignored -> {});
    }

    private String runCommand(
            List<String> command, java.util.function.IntConsumer progress)
            throws IOException {
        var builder = new ProcessBuilder(command).redirectErrorStream(true);
        var currentPath = builder.environment().getOrDefault("PATH", "");
        builder.environment().put(
                "PATH", executable.getParent() + java.io.File.pathSeparator + currentPath);
        var process = org.pathlab.forge.runtime.ChildProcessContainment.global()
                .register(builder.start());
        var output = new ByteArrayOutputStream();
        var reader = new Thread(
                () -> copyBounded(process.getInputStream(), output, process, progress),
                "pathlab-vips-output");
        reader.setDaemon(true);
        reader.start();
        try {
            if (!process.waitFor(OPERATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IOException("libvips operation timed out");
            }
            reader.join(10_000);
            if (process.exitValue() != 0) {
                throw new IOException("libvips operation failed: " + tail(
                        output.toString(StandardCharsets.UTF_8)));
            }
            return output.toString(StandardCharsets.UTF_8);
        } catch (InterruptedException error) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("libvips operation was interrupted", error);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    static List<String> commandLine(Path executable, List<String> arguments) {
        var profile = org.pathlab.forge.runtime.RuntimeProfile.system();
        // libvips scales beyond the reader-oriented worker limit because its JPEG
        // encoders operate on independent tiles. Ten workers was the highest
        // byte-identical throughput win on the 12-thread reference workstation;
        // the 8 GB / 6-core profile remains bounded at five.
        var logicalProcessors =
                org.pathlab.forge.runtime.RuntimeProfile.configuredLogicalProcessors();
        var concurrentJobs = logicalProcessors >= 12
                        && profile.processTreeLimitBytes() >= 16L * 1024 * 1024 * 1024
                ? 2
                : 1;
        var reservedCpu = logicalProcessors <= 6
                ? profile.vipsConcurrency()
                : Math.max(1, logicalProcessors - 2);
        var defaultConcurrency = Math.min(
                10,
                Math.min(profile.vipsConcurrency(), Math.max(1, reservedCpu / concurrentJobs)));
        var concurrency = Integer.getInteger(
                "pathlab.forge.vips.concurrency", defaultConcurrency);
        concurrency = Math.max(1, Math.min(concurrency, profile.vipsConcurrency()));
        var command = new ArrayList<String>();
        command.add(executable.toString());
        command.add("--vips-concurrency=" + concurrency);
        command.add("--vips-cache-max-memory=" + profile.vipsCacheBytes());
        command.add("--vips-cache-max-files=" + profile.vipsCacheFiles());
        command.add("--vips-cache-max=" + profile.vipsCacheOperations());
        command.addAll(arguments);
        return List.copyOf(command);
    }

    private static void copyBounded(
            InputStream input,
            ByteArrayOutputStream output,
            Process process,
            java.util.function.IntConsumer progress) {
        try (input; output) {
            var buffer = new byte[8192];
            var progressPattern = java.util.regex.Pattern.compile("(\\d{1,3})% complete");
            var roiPattern = java.util.regex.Pattern.compile("PATHLAB_ROI=(\\d+)");
            var progressTail = "";
            var lastProgress = -1;
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > 4 * 1024 * 1024) {
                    process.destroyForcibly();
                    return;
                }
                output.write(buffer, 0, read);
                var decoded = progressTail
                        + new String(buffer, 0, read, StandardCharsets.UTF_8);
                var matcher = progressPattern.matcher(decoded);
                while (matcher.find()) {
                    var value = Math.min(100, Integer.parseInt(matcher.group(1)));
                    if (value > lastProgress) {
                        progress.accept(value);
                        lastProgress = value;
                    }
                }
                var roiMatcher = roiPattern.matcher(decoded);
                while (roiMatcher.find()) {
                    progress.accept(Integer.parseInt(roiMatcher.group(1)));
                }
                progressTail = decoded.substring(Math.max(0, decoded.length() - 64));
            }
        } catch (IOException ignored) {
            process.destroyForcibly();
        }
    }

    private static Path findExecutable(Path candidate) {
        if (Files.isRegularFile(candidate)) {
            return candidate.toAbsolutePath().normalize();
        }
        if (!Files.isDirectory(candidate)) {
            return null;
        }
        var expected = java.io.File.separatorChar == '\\' ? "vips.exe" : "vips";
        try (Stream<Path> paths = Files.find(
                candidate,
                3,
                (path, attributes) -> attributes.isRegularFile()
                        && path.getFileName().toString().equalsIgnoreCase(expected))) {
            return paths.findFirst().map(path -> path.toAbsolutePath().normalize()).orElse(null);
        } catch (IOException ignored) {
            return null;
        }
    }

    private void requireAvailable() {
        if (!available()) {
            throw new IllegalStateException(
                    "Install libvips locally or set PATHLAB_FORGE_VIPS");
        }
    }

    private static void requireNonempty(Path path, String label) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) == 0) {
            throw new IOException(label + " was not created");
        }
    }

    private static String tail(String value) {
        var normalized = value.strip();
        return normalized.length() <= 800
                ? normalized
                : normalized.substring(normalized.length() - 800);
    }
}
