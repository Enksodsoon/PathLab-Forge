package org.pathlab.forge.conversion;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.pathlab.forge.library.DatasetFormat;
import org.pathlab.forge.runtime.ChildProcessContainment;

final class QuPathRuntime {
    static final long ACCELERATED_SECONDS_BUDGET_PIXELS = 200_000_000L;
    static final long STANDARD_SECONDS_BUDGET_PIXELS = 80_000_000L;
    private static final Duration EXPORT_TIMEOUT = Duration.ofMinutes(1);
    private final Path javaExecutable;
    private final Path appDirectory;

    private QuPathRuntime(Path javaExecutable, Path appDirectory) {
        this.javaExecutable = javaExecutable;
        this.appDirectory = appDirectory;
    }

    static QuPathRuntime discover() {
        if (!Boolean.parseBoolean(
                System.getProperty("pathlab.forge.qupath.enabled", "true"))) {
            return new QuPathRuntime(null, null);
        }
        var javaExecutable = configuredJava();
        if (javaExecutable == null || javaFeatureVersion() < 25) {
            return new QuPathRuntime(null, null);
        }
        for (var home : candidateHomes()) {
            var app = home.resolve("app");
            if (Files.isRegularFile(app.resolve("qupath-extension-bioformats-0.7.0.jar"))
                    && Files.isRegularFile(app.resolve("qupath-core-0.7.0.jar"))) {
                return new QuPathRuntime(javaExecutable, app.toAbsolutePath().normalize());
            }
        }
        return new QuPathRuntime(null, null);
    }

    boolean available() {
        return javaExecutable != null && appDirectory != null;
    }

    boolean supports(DatasetFormat format) {
        return available() && format == DatasetFormat.VSI;
    }

    void writePyramidalOme(ConversionRequest request, Path output) throws IOException {
        if (!available()) {
            throw new IOException("The QuPath direct writer is unavailable");
        }
        Files.createDirectories(output.toAbsolutePath().normalize().getParent());
        Files.deleteIfExists(output);
        var command = commandLine(javaExecutable, appDirectory, request, output);
        var process = ChildProcessContainment.global()
                .register(new ProcessBuilder(command).redirectErrorStream(true).start());
        var capture = new ByteArrayOutputStream();
        var reader = new Thread(
                () -> copyBounded(process.getInputStream(), capture, process),
                "pathlab-qupath-output");
        reader.setDaemon(true);
        reader.start();
        try {
            if (!process.waitFor(EXPORT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IOException(
                        "SECONDS_BUDGET_EXCEEDED: direct OME export exceeded 59 seconds");
            }
            reader.join(5_000);
            if (process.exitValue() != 0 || !Files.isRegularFile(output)
                    || Files.size(output) == 0) {
                Files.deleteIfExists(output);
                throw new IOException(
                        "QuPath direct OME export failed: "
                                + tail(capture.toString(StandardCharsets.UTF_8)));
            }
        } catch (InterruptedException error) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("QuPath direct OME export was interrupted", error);
        }
    }

    static void requireSecondsBudget(ConversionRequest request, boolean accelerated) {
        var pixels = Math.multiplyExact(
                (long) request.outputWidth(), request.outputHeight());
        var limit = accelerated
                ? ACCELERATED_SECONDS_BUDGET_PIXELS
                : STANDARD_SECONDS_BUDGET_PIXELS;
        if (pixels > limit) {
            throw new IllegalStateException(
                    "SECONDS_BUDGET_EXCEEDED: this export contains "
                            + pixels
                            + " output pixels; the 8 GB / 6-core seconds profile allows at most "
                            + limit
                            + ". Increase downsample or reduce the crop.");
        }
    }

    static List<String> commandLine(
            Path javaExecutable,
            Path appDirectory,
            ConversionRequest request,
            Path output) {
        return List.of(
                javaExecutable.toString(),
                "-XX:ActiveProcessorCount=6",
                "-Xmx4g",
                "-Djava.awt.headless=true",
                "-cp",
                appDirectory + java.io.File.separator + "*",
                "qupath.QuPath",
                "convert-ome",
                "--series=" + request.seriesIndex(),
                "--downsample=" + request.downsample(),
                "--crop=" + request.cropX() + "," + request.cropY() + ","
                        + request.cropWidth() + "," + request.cropHeight(),
                "--compression=UNCOMPRESSED",
                "--tile-size=512",
                "--overwrite",
                request.source().toString(),
                output.toString());
    }

    private static Path configuredJava() {
        var configured = System.getProperty("pathlab.forge.qupath.java", "").trim();
        if (!configured.isEmpty()) {
            var path = Path.of(configured).toAbsolutePath().normalize();
            return Files.isRegularFile(path) ? path : null;
        }
        var name = java.io.File.separatorChar == '\\' ? "java.exe" : "java";
        var path = Path.of(System.getProperty("java.home"), "bin", name)
                .toAbsolutePath()
                .normalize();
        return Files.isRegularFile(path) ? path : null;
    }

    private static int javaFeatureVersion() {
        var value = System.getProperty("java.specification.version", "0");
        var normalized = value.startsWith("1.") ? value.substring(2) : value;
        var end = normalized.indexOf('.');
        return Integer.parseInt(end < 0 ? normalized : normalized.substring(0, end));
    }

    private static List<Path> candidateHomes() {
        var candidates = new ArrayList<Path>();
        var configured = System.getProperty("pathlab.forge.qupath.home", "").trim();
        if (configured.isEmpty()) {
            configured = System.getenv().getOrDefault("PATHLAB_FORGE_QUPATH_HOME", "").trim();
        }
        if (!configured.isEmpty()) {
            candidates.add(Path.of(configured));
        }
        var localAppData = System.getenv().getOrDefault("LOCALAPPDATA", "").trim();
        if (!localAppData.isEmpty()) {
            try (Stream<Path> homes = Files.list(Path.of(localAppData))) {
                homes.filter(Files::isDirectory)
                        .filter(path -> path.getFileName().toString().startsWith("QuPath-"))
                        .sorted(Comparator.reverseOrder())
                        .forEach(candidates::add);
            } catch (IOException ignored) {
                // The explicit home remains available when LocalAppData cannot be scanned.
            }
        }
        return List.copyOf(candidates);
    }

    private static void copyBounded(
            InputStream input, ByteArrayOutputStream output, Process process) {
        try (input; output) {
            var buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > 4 * 1024 * 1024) {
                    process.destroyForcibly();
                    return;
                }
                output.write(buffer, 0, read);
            }
        } catch (IOException ignored) {
            process.destroyForcibly();
        }
    }

    private static String tail(String value) {
        var normalized = value.strip();
        return normalized.length() <= 800
                ? normalized
                : normalized.substring(normalized.length() - 800);
    }
}
