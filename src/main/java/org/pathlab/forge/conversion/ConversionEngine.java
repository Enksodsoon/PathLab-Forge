package org.pathlab.forge.conversion;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import org.pathlab.forge.reader.RuntimeCatalog;
import org.pathlab.forge.reader.DatasetProbe;
import org.pathlab.forge.reader.ReaderDescriptor;
import org.pathlab.forge.reader.ViewDefinition;
import org.pathlab.forge.reader.ImportProbeException;

public interface ConversionEngine extends AutoCloseable {
    boolean available();

    String runtimeDescription();

    default Optional<RuntimeCatalog> runtimeCatalog() {
        return Optional.empty();
    }

    default DatasetProbe.Result probe(Path source) throws IOException, ImportProbeException {
        var series = inspect(source);
        if (series.isEmpty()) throw new IOException("Reader returned no image series");
        var extension = source.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        var descriptor = runtimeCatalog().stream().flatMap(catalog -> catalog.formats().stream())
                .filter(format -> format.extensions().stream()
                        .anyMatch(value -> extension.endsWith("." + value)))
                .findFirst()
                .orElse(new ReaderDescriptor(
                        "UNKNOWN", "detected-image", "Detected image", List.of(),
                        series.stream().anyMatch(item -> item.sizeZ() > 1 || item.sizeT() > 1
                                || item.channels() > 3),
                        series.stream().anyMatch(item -> item.resolutionCount() > 1),
                        false, supportsDirectTiles()));
        var fingerprint = runtimeCatalog().map(RuntimeCatalog::fingerprint).orElse("0".repeat(64));
        return new DatasetProbe.Result(
                descriptor, descriptor.displayName(), List.of(source), fingerprint);
    }

    List<SeriesInfo> inspect(Path source) throws IOException;

    void convert(Path source, int seriesIndex, Path output) throws IOException;

    default PreviewSource renderPreview(
            Path source, int seriesIndex, Path output, int maxDimension) throws IOException {
        throw new IOException("This conversion engine does not support bounded previews");
    }

    default boolean supportsDirectTiles() {
        return false;
    }

    default DirectTileSource directTileSource(Path source, int seriesIndex) throws IOException {
        throw new IOException("This conversion engine does not support direct tiles");
    }

    default byte[] readDirectTile(
            Path source, int seriesIndex, int level, int tileX, int tileY)
            throws IOException {
        throw new IOException("This conversion engine does not support direct tiles");
    }

    default DirectTileSource directTileSource(Path source, ViewDefinition view) throws IOException {
        return directTileSource(source, view.series());
    }

    default byte[] readViewTile(
            Path source, ViewDefinition view, int level, int tileX, int tileY) throws IOException {
        return readDirectTile(source, view.series(), level, tileX, tileY);
    }

    default byte[] seriesThumbnail(Path source, int seriesIndex, int maxDimension)
            throws IOException {
        throw new IOException("This conversion engine does not support series thumbnails");
    }

    default RgbRegion readRgbRegion(
            Path source, int seriesIndex, int x, int y, int width, int height)
            throws IOException {
        throw new IOException("This conversion engine does not support raw RGB regions");
    }

    default RgbRegion readRgbRegion(
            Path source, int seriesIndex, int z, int t, int x, int y, int width, int height)
            throws IOException {
        if (z != 0 || t != 0) throw new IOException("This reader has not qualified exact Z/T RGB regions");
        return readRgbRegion(source, seriesIndex, x, y, width, height);
    }

    default void closeDirectSource(Path source) throws IOException {
        // Engines without persistent direct readers have nothing to release.
    }

    @Override
    default void close() throws IOException {
        // Engines without persistent resources have nothing to release.
    }

    default void convert(ConversionRequest request, Path output) throws IOException {
        if (!request.isFullSeries() || request.downsample() != 1.0) {
            throw new IOException("This conversion engine does not support crop or downsample");
        }
        convert(request.source(), request.seriesIndex(), output);
    }

    default boolean supportsParallelRegions() {
        return false;
    }

    default List<Path> convertRegions(
            ConversionRequest request, Path outputDirectory, int workers) throws IOException {
        throw new IOException("This conversion engine does not support parallel regions");
    }

    default List<Path> convertRegions(
            ConversionRequest request,
            Path outputDirectory,
            int workers,
            BiConsumer<Integer, Integer> progress)
            throws IOException {
        var outputs = convertRegions(request, outputDirectory, workers);
        progress.accept(outputs.size(), outputs.size());
        return outputs;
    }

    default List<Path> convertViewRegions(
            ConversionRequest request,
            ViewDefinition view,
            Path outputDirectory,
            int workers,
            BiConsumer<Integer, Integer> progress) throws IOException {
        throw new IOException("This conversion engine cannot render multidimensional views");
    }
}
