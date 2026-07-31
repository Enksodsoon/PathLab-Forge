package org.pathlab.forge.derivative;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
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

    default void validateOmeGeometry(Path omeTiff, int width, int height) throws IOException {
        // Engines without a metadata reader retain the signature and DZI geometry checks.
    }

    default void validateOmeProfile(
            Path omeTiff,
            int width,
            int height,
            OmeDynamicProfile profile,
            int jpegQuality)
            throws IOException {
        validateOmeGeometry(omeTiff, width, height);
    }

    default void assembleRegionsFinal(
            List<Path> regions, Path pyramidalOme, int width, int height) throws IOException {
        throw new IOException("Direct final OME assembly is unavailable");
    }

    default void assembleRegionsFinal(
            List<Path> regions,
            Path pyramidalOme,
            int width,
            int height,
            double downsample)
            throws IOException {
        assembleRegionsFinal(regions, pyramidalOme, width, height);
    }

    default void assembleRegionsFinal(
            List<Path> regions,
            Path pyramidalOme,
            int width,
            int height,
            double downsample,
            OmeDynamicProfile profile,
            int jpegQuality)
            throws IOException {
        assembleRegionsFinal(regions, pyramidalOme, width, height, downsample);
    }

    default boolean supportsDirectDziFromRegions() {
        return false;
    }

    default DerivativeInfo generateDziFromRegions(
            List<Path> regions,
            Path outputRoot,
            int width,
            int height,
            double downsample,
            Consumer<DerivativeProgress> progress)
            throws IOException {
        throw new IOException("Direct DZI region rendering is unavailable");
    }

    void optimizeOme(Path renderedOme, Path pyramidalOme, int width, int height)
            throws IOException;

    DerivativeInfo generateDzi(Path omeTiff, Path outputRoot, int width, int height)
            throws IOException;

    /**
     * Builds a disposable local-viewer pyramid. Production packages still use
     * {@link #generateDzi}; viewer caches may skip the expensive adaptive
     * quality search because they are never uploaded or approved artifacts.
     */
    default DerivativeInfo generateViewerDzi(
            Path omeTiff, Path outputRoot, int width, int height) throws IOException {
        return generateDzi(omeTiff, outputRoot, width, height);
    }

    default void generateViewerThumbnail(
            Path source, int seriesIndex, Path output, int maxDimension) throws IOException {
        throw new IOException("Bounded viewer thumbnail generation is unavailable");
    }

    default DerivativeInfo generateDzi(
            Path omeTiff,
            Path outputRoot,
            int width,
            int height,
            Consumer<DerivativeProgress> progress)
            throws IOException {
        var result = generateDzi(omeTiff, outputRoot, width, height);
        progress.accept(new DerivativeProgress(
                "DZI_VALIDATING", result.tileCount(), result.tileCount()));
        return result;
    }
}
