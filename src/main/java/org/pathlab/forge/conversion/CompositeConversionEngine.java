package org.pathlab.forge.conversion;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BiConsumer;
import org.pathlab.forge.reader.DatasetProbe;
import org.pathlab.forge.reader.ImportProbeException;
import org.pathlab.forge.reader.ImportDiagnostic;
import org.pathlab.forge.reader.ReaderDescriptor;
import org.pathlab.forge.reader.RuntimeCatalog;
import org.pathlab.forge.reader.ViewDefinition;

/** Routes vendor formats to bounded native readers and everything else to Bio-Formats. */
public final class CompositeConversionEngine implements ConversionEngine {
    private final ConversionEngine primary;
    private final MoticMdsEngine mds;
    private final SdpcEngine sdpc;

    public static CompositeConversionEngine discover(Path dataRoot) {
        return new CompositeConversionEngine(BioFormatsEngine.discover(dataRoot),
                new MoticMdsEngine(), SdpcEngine.discover(dataRoot));
    }

    CompositeConversionEngine(ConversionEngine primary, MoticMdsEngine mds, SdpcEngine sdpc) {
        this.primary = primary; this.mds = mds; this.sdpc = sdpc;
    }

    // This flag controls whether legacy imports may bypass probing. Keep it tied to the
    // universal primary runtime; format-specific readers are still routed by probe().
    @Override public boolean available() { return primary.available(); }
    @Override public String runtimeDescription() {
        return primary.runtimeDescription() + "; " + mds.runtimeDescription() + "; " + sdpc.runtimeDescription();
    }

    @Override public Optional<RuntimeCatalog> runtimeCatalog() {
        var base = primary.runtimeCatalog();
        var formats = new ArrayList<ReaderDescriptor>();
        var version = new StringBuilder();
        base.ifPresent(catalog -> { formats.addAll(catalog.formats()); version.append(catalog.runtimeVersion()); });
        formats.add(new ReaderDescriptor("PATHLAB_MDS", "motic-mds-v1", "Motic MDS",
                List.of("mds"), false, true, false, true));
        if (sdpc.available()) formats.add(new ReaderDescriptor("SDPC_NATIVE", "sqray-sdpc", "SDPC",
                List.of("sdpc"), false, true, false, true));
        if (formats.isEmpty()) return Optional.empty();
        version.append(" + PathLab MDS");
        if (sdpc.available()) version.append(" + SDPC native");
        var canonical = version + "\n" + formats.stream().map(ReaderDescriptor::readerId).sorted()
                .collect(java.util.stream.Collectors.joining("\n"));
        return Optional.of(new RuntimeCatalog(version.toString(), sha256(canonical), formats));
    }

    @Override public DatasetProbe.Result probe(Path source) throws IOException, ImportProbeException {
        try {
            return engine(source).probe(source);
        } catch (IOException | ImportProbeException error) {
            if (source.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".isyntax")) {
                throw new ImportProbeException(new ImportDiagnostic(
                        ImportDiagnostic.Code.CODEC_UNAVAILABLE,
                        "Philips iSyntax decoding requires a locally licensed Philips Pathology SDK; no SDK reader is installed",
                        List.of(source.toAbsolutePath().normalize())));
            }
            throw error;
        }
    }
    @Override public List<SeriesInfo> inspect(Path source) throws IOException { return engine(source).inspect(source); }
    @Override public void convert(Path source, int seriesIndex, Path output) throws IOException { engine(source).convert(source, seriesIndex, output); }
    @Override public PreviewSource renderPreview(Path source, int seriesIndex, Path output, int maxDimension) throws IOException {
        return engine(source).renderPreview(source, seriesIndex, output, maxDimension);
    }
    @Override public boolean supportsDirectTiles() { return true; }
    @Override public DirectTileSource directTileSource(Path source, int seriesIndex) throws IOException { return engine(source).directTileSource(source, seriesIndex); }
    @Override public byte[] readDirectTile(Path source, int seriesIndex, int level, int tileX, int tileY) throws IOException {
        return engine(source).readDirectTile(source, seriesIndex, level, tileX, tileY);
    }
    @Override public DirectTileSource directTileSource(Path source, ViewDefinition view) throws IOException { return engine(source).directTileSource(source, view); }
    @Override public byte[] readViewTile(Path source, ViewDefinition view, int level, int tileX, int tileY) throws IOException {
        return engine(source).readViewTile(source, view, level, tileX, tileY);
    }
    @Override public byte[] seriesThumbnail(Path source, int seriesIndex, int maxDimension) throws IOException {
        return engine(source).seriesThumbnail(source, seriesIndex, maxDimension);
    }
    @Override public RgbRegion readRgbRegion(Path source, int seriesIndex, int x, int y, int width, int height) throws IOException {
        return engine(source).readRgbRegion(source, seriesIndex, x, y, width, height);
    }
    @Override public void closeDirectSource(Path source) throws IOException { engine(source).closeDirectSource(source); }
    @Override public void convert(ConversionRequest request, Path output) throws IOException { engine(request.source()).convert(request, output); }
    @Override public boolean supportsParallelRegions() { return true; }
    @Override public List<Path> convertRegions(ConversionRequest request, Path outputDirectory, int workers) throws IOException {
        return engine(request.source()).convertRegions(request, outputDirectory, workers);
    }
    @Override public List<Path> convertRegions(ConversionRequest request, Path outputDirectory, int workers,
            BiConsumer<Integer, Integer> progress) throws IOException {
        return engine(request.source()).convertRegions(request, outputDirectory, workers, progress);
    }
    @Override public List<Path> convertViewRegions(ConversionRequest request, ViewDefinition view,
            Path outputDirectory, int workers, BiConsumer<Integer, Integer> progress) throws IOException {
        return engine(request.source()).convertViewRegions(request, view, outputDirectory, workers, progress);
    }

    @Override public void close() throws IOException {
        IOException failure = null;
        for (var engine : List.of(primary, mds, sdpc)) {
            try { engine.close(); } catch (IOException error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        }
        if (failure != null) throw failure;
    }

    private ConversionEngine engine(Path source) {
        var name = source.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".mds")) return mds;
        if (name.endsWith(".sdpc")) return sdpc;
        return primary;
    }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
