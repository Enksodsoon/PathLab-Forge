package org.pathlab.forge.derivative;

import java.io.IOException;
import java.nio.file.Path;
import org.pathlab.forge.conversion.ConversionRequest;

public interface DerivativeEngine {
    boolean available();

    String description();

    default boolean supportsOmeRendering() {
        return false;
    }

    default void renderOme(ConversionRequest request, Path output) throws IOException {
        throw new IOException("OME rendering is unavailable");
    }

    void optimizeOme(Path renderedOme, Path pyramidalOme, int width, int height)
            throws IOException;

    DerivativeInfo generateDzi(Path omeTiff, Path outputRoot, int width, int height)
            throws IOException;
}
