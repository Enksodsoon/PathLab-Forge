package org.pathlab.forge.conversion;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public interface ConversionEngine {
    boolean available();

    String runtimeDescription();

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
}
