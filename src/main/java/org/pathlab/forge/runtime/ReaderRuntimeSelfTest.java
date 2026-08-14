package org.pathlab.forge.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.pathlab.forge.conversion.CompositeConversionEngine;
import org.pathlab.forge.derivative.VipsRuntime;
import org.pathlab.forge.library.ForgePaths;

/** Bounded process-level acceptance for a frozen reader runtime. */
public final class ReaderRuntimeSelfTest {
    private ReaderRuntimeSelfTest() {}

    public static String run(ForgePaths paths, List<Path> sources) throws IOException {
        var inventory = ReaderRuntimeInventory.inspect(paths.dataRoot());
        requireAvailable(inventory, "bioformats");
        requireAvailable(inventory, "libvips");
        var results = new ArrayList<Result>();
        try (var conversion = CompositeConversionEngine.discover(paths.dataRoot())) {
            var vips = VipsRuntime.discover(paths.dataRoot());
            var conversionCatalog = conversion.runtimeCatalog()
                    .orElseThrow(() -> new IOException("Scientific reader catalog is unavailable"));
            var vipsCatalog = vips.runtimeCatalog()
                    .orElseThrow(() -> new IOException("Ordinary-image reader catalog is unavailable"));
            if (conversionCatalog.formats().stream()
                    .noneMatch(format -> "BIO_FORMATS".equals(format.engine()))) {
                throw new IOException("Bio-Formats catalog did not start in the packaged Java runtime");
            }
            if (vipsCatalog.formats().stream()
                    .noneMatch(format -> "LIBVIPS".equals(format.engine()))) {
                throw new IOException("libvips loaders did not start in the packaged runtime");
            }
            for (var source : sources) results.add(decode(source, conversion, vips));
            return json(inventory, conversionCatalog.formats().size(), vipsCatalog.formats().size(), results);
        }
    }

    private static Result decode(Path source, CompositeConversionEngine conversion, VipsRuntime vips)
            throws IOException {
        var normalized = source.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) throw new IOException("Self-test source does not exist");
        IOException primaryFailure = null;
        try {
            var probe = conversion.probe(normalized);
            var series = conversion.inspect(normalized);
            if (series.isEmpty()) throw new IOException("Reader returned no image series");
            var selected = series.get(0);
            var width = Math.min(96, selected.width());
            var height = Math.min(96, selected.height());
            var x = Math.max(0, (selected.width() - width) / 2);
            var y = Math.max(0, (selected.height() - height) / 2);
            var region = conversion.readRgbRegion(normalized, selected.index(), x, y, width, height);
            if (region.interleavedRgb().length != width * height * 3) {
                throw new IOException("Reader returned an incomplete RGB sample");
            }
            return new Result(normalized.getFileName().toString(), "OPENED", probe.descriptor().engine(),
                    width * height, "Bounded RGB region decoded");
        } catch (org.pathlab.forge.reader.ImportProbeException error) {
            primaryFailure = new IOException(error.diagnostic().code().name(), error);
        } catch (IOException error) {
            primaryFailure = error;
        }
        Path thumbnail = null;
        try {
            var probe = vips.probe(normalized);
            thumbnail = Files.createTempFile("pathlab-reader-self-test-", ".jpg");
            vips.generateViewerThumbnail(normalized, 0, thumbnail, 96);
            var bytes = Files.size(thumbnail);
            if (bytes < 16 || bytes > 4L * 1024 * 1024) {
                throw new IOException("libvips returned an invalid bounded pixel sample");
            }
            return new Result(normalized.getFileName().toString(), "OPENED", probe.descriptor().engine(),
                    bytes, "Bounded thumbnail decoded after scientific-reader fallback");
        } catch (IOException fallbackFailure) {
            fallbackFailure.addSuppressed(primaryFailure);
            throw new IOException("No packaged reader pixel-decoded " + normalized.getFileName(), fallbackFailure);
        } finally {
            if (thumbnail != null) Files.deleteIfExists(thumbnail);
            try { conversion.closeDirectSource(normalized); } catch (IOException ignored) { }
        }
    }

    private static void requireAvailable(List<ReaderRuntimeInventory.Status> inventory, String id)
            throws IOException {
        if (inventory.stream().noneMatch(item -> item.id().equals(id) && item.available())) {
            throw new IOException("Required packaged reader is unavailable: " + id);
        }
    }

    private static String json(List<ReaderRuntimeInventory.Status> inventory, int scientificFormats,
            int ordinaryFormats, List<Result> results) {
        var packaged = inventory.stream().filter(item -> item.source().equals("PACKAGED"))
                .map(ReaderRuntimeInventory.Status::fingerprint).filter(value -> !value.isBlank())
                .findFirst().orElse("");
        return "{\"status\":\"PASS\",\"platform\":" + quote(ReaderRuntimeManifest.currentPlatform())
                + ",\"runtimeFingerprint\":" + quote(packaged)
                + ",\"containedProcesses\":true,\"scientificFormats\":" + scientificFormats
                + ",\"ordinaryFormats\":" + ordinaryFormats + ",\"sources\":["
                + results.stream().map(Result::json).collect(java.util.stream.Collectors.joining(","))
                + "]}";
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private record Result(String name, String status, String engine, long sampleBytes, String detail) {
        String json() {
            return "{\"name\":" + quote(name) + ",\"status\":" + quote(status)
                    + ",\"engine\":" + quote(engine) + ",\"sampleBytes\":" + sampleBytes
                    + ",\"detail\":" + quote(detail) + "}";
        }
    }
}
