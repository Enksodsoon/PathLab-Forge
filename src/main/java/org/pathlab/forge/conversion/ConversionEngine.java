package org.pathlab.forge.conversion;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public interface ConversionEngine {
    boolean available();

    String runtimeDescription();

    List<SeriesInfo> inspect(Path source) throws IOException;

    void convert(Path source, int seriesIndex, Path output) throws IOException;
}
