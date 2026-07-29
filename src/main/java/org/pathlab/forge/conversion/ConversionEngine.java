package org.pathlab.forge.conversion;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public interface ConversionEngine {
    boolean available();

    String runtimeDescription();

    List<SeriesInfo> inspect(Path source) throws IOException;

    void convert(Path source, int seriesIndex, Path output) throws IOException;

    default void convert(ConversionRequest request, Path output) throws IOException {
        if (!request.isFullSeries() || request.downsample() != 1) {
            throw new IOException("This conversion engine does not support crop or downsample");
        }
        convert(request.source(), request.seriesIndex(), output);
    }
}
