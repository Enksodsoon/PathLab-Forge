package org.pathlab.forge.derivative;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
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

    default void assembleRegions(List<Path> regions, Path renderedOme) throws IOException {
        throw new IOException("Rendered-region assembly is unavailable");
    }

    default boolean supportsDirectFinalOme() {
        return false;
    }

    default void assembleRegionsFinal(
            List<Path> regions, Path pyramidalOme, int width, int height) throws IOException {
        throw new IOException("Direct final OME assembly is unavailable");
    }

    void optimizeOme(Path renderedOme, Path pyramidalOme, int width, int height)
            throws IOException;

    DerivativeInfo generateDzi(Path omeTiff, Path outputRoot, int width, int height)
            throws IOException;
}
