package org.pathlab.forge.conversion;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** Opt-in, bounded audit for private SVS files. Paths and reports remain outside the repository. */
class SvsCompatibilityAuditTest {
    @Test void opensEveryDiscoveredSvsOrIdentifiesStructuralCorruption() throws Exception {
        var configured = System.getProperty("pathlab.forge.test.svsRoots", "");
        assumeTrue(!configured.isBlank());
        var sources = new ArrayList<Path>();
        for (var value : configured.split("\\|")) {
            var root = Path.of(value).toAbsolutePath().normalize();
            if (!Files.isDirectory(root)) continue;
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (attributes.isRegularFile()
                            && file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".svs")) {
                        sources.add(file.toAbsolutePath().normalize());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override public FileVisitResult visitFileFailed(Path file, IOException error) {
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        sources.sort(Comparator.comparing(Path::toString, String.CASE_INSENSITIVE_ORDER));
        assumeTrue(!sources.isEmpty());

        var dataRoot = Path.of(System.getenv("LOCALAPPDATA"), "PathLab Forge");
        var results = new ArrayList<Result>();
        try (var engine = BioFormatsEngine.discover(dataRoot)) {
            assumeTrue(engine.available());
            for (var source : sources) {
                var started = System.nanoTime();
                var structural = structuralProblem(source);
                if (structural != null) {
                    results.add(new Result(source, Files.size(source), "CORRUPT_TRUNCATED",
                            0, 0, 0, 0, elapsedMs(started), structural));
                    continue;
                }
                try {
                    var info = engine.inspect(source).get(0);
                    var tileSource = engine.directTileSource(source, 0);
                    var level = tileSource.maximumLevel();
                    var columns = Math.max(1, (info.width() + tileSource.tileSize() - 1)
                            / tileSource.tileSize());
                    var rows = Math.max(1, (info.height() + tileSource.tileSize() - 1)
                            / tileSource.tileSize());
                    var tile = engine.readDirectTile(source, 0, level, columns / 2, rows / 2);
                    if (tile.length < 128) throw new IOException("Decoded JPEG tile is unexpectedly small");
                    results.add(new Result(source, Files.size(source), "OPENED", info.width(),
                            info.height(), info.resolutionCount(), tile.length, elapsedMs(started), ""));
                } catch (Exception error) {
                    results.add(new Result(source, Files.size(source), "READER_FAILURE",
                            0, 0, 0, 0, elapsedMs(started), safeDetail(error)));
                } finally {
                    engine.closeDirectSource(source);
                }
            }
        }
        var report = System.getProperty("pathlab.forge.test.svsReport", "");
        if (!report.isBlank()) writeReports(Path.of(report), results);
        var failures = results.stream().filter(item -> item.status.equals("READER_FAILURE")).toList();
        if (!failures.isEmpty()) fail("Readable-looking SVS failures: " + failures);
    }

    private static String structuralProblem(Path source) {
        try {
            var size = Files.size(source);
            if (size < 16) return "File is shorter than a TIFF header";
            var bytes = readPrefix(source, 16);
            var order = bytes[0] == 'I' && bytes[1] == 'I' ? ByteOrder.LITTLE_ENDIAN
                    : bytes[0] == 'M' && bytes[1] == 'M' ? ByteOrder.BIG_ENDIAN : null;
            if (order == null) return "File does not contain a TIFF/SVS byte-order header";
            var buffer = ByteBuffer.wrap(bytes).order(order);
            var magic = Short.toUnsignedInt(buffer.getShort(2));
            long offset;
            if (magic == 42) offset = Integer.toUnsignedLong(buffer.getInt(4));
            else if (magic == 43) offset = buffer.getLong(8);
            else return null;
            if (offset <= 0 || offset >= size) {
                return "First TIFF IFD offset " + Long.toUnsignedString(offset)
                        + " is outside file length " + size;
            }
            if (magic == 42) {
                var ifd = readAt(source, offset, 14);
                if (ifd.length < 14) return "First TIFF IFD is truncated";
                var table = ByteBuffer.wrap(ifd).order(order);
                var entries = Short.toUnsignedInt(table.getShort(0));
                var firstType = Short.toUnsignedInt(table.getShort(4));
                if (entries == 0 || entries > 4096 || firstType < 1 || firstType > 18) {
                    return "First TIFF IFD is invalid (entries=" + entries
                            + ", firstType=" + firstType + ")";
                }
            }
            return null;
        } catch (IOException error) { return "Could not read TIFF header: " + error.getMessage(); }
    }

    private static byte[] readPrefix(Path source, int count) throws IOException {
        try (var input = Files.newInputStream(source)) { return input.readNBytes(count); }
    }

    private static byte[] readAt(Path source, long offset, int count) throws IOException {
        try (var channel = java.nio.channels.FileChannel.open(source)) {
            var buffer = ByteBuffer.allocate(count);
            channel.position(offset);
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) { }
            return java.util.Arrays.copyOf(buffer.array(), buffer.position());
        }
    }

    private static long elapsedMs(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }

    private static String safeDetail(Throwable error) {
        var values = new ArrayList<String>();
        for (var current = error; current != null && values.size() < 6; current = current.getCause()) {
            var value = current.getMessage() == null
                    ? current.getClass().getSimpleName() : current.getMessage();
            values.add(value.replace('\r', ' ').replace('\n', ' ').strip());
        }
        return String.join(" -> ", values);
    }

    private static void writeReports(Path html, List<Result> results) throws IOException {
        var target = html.toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());
        var csv = target.resolveSibling(target.getFileName().toString().replaceAll("\\.html$", ".csv"));
        var sizeCounts = new HashMap<Long, Integer>();
        results.forEach(item -> sizeCounts.merge(item.bytes, 1, Integer::sum));
        var csvBody = new StringBuilder("path,bytes,size_duplicates,status,width,height,levels,tile_bytes,elapsed_ms,detail\n");
        for (var item : results) csvBody.append(csv(item.path.toString())).append(',')
                .append(item.bytes).append(',').append(sizeCounts.get(item.bytes)).append(',')
                .append(item.status).append(',').append(item.width).append(',').append(item.height)
                .append(',').append(item.levels).append(',').append(item.tileBytes).append(',')
                .append(item.elapsedMs).append(',').append(csv(item.detail)).append('\n');
        Files.writeString(csv, csvBody, StandardCharsets.UTF_8);
        var opened = results.stream().filter(item -> item.status.equals("OPENED")).count();
        var corrupt = results.stream().filter(item -> item.status.equals("CORRUPT_TRUNCATED")).count();
        var failed = results.size() - opened - corrupt;
        var rows = new StringBuilder();
        for (var item : results) rows.append("<tr><td>").append(html(item.path.toString()))
                .append("</td><td>").append(item.bytes).append("</td><td>")
                .append(sizeCounts.get(item.bytes)).append("</td><td class=\"")
                .append(item.status.equals("OPENED") ? "pass" : "warn").append("\">")
                .append(item.status).append("</td><td>").append(item.width).append('×')
                .append(item.height).append("</td><td>").append(item.levels).append("</td><td>")
                .append(item.tileBytes).append("</td><td>").append(item.elapsedMs)
                .append("</td><td>").append(html(item.detail)).append("</td></tr>");
        var document = """
                <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                <title>Forge SVS compatibility audit</title><style>:root{color-scheme:dark;background:#091411;color:#e8f4ee;font-family:Segoe UI,sans-serif}body{margin:0;padding:28px}.wrap{max-width:1500px;margin:auto}.cards{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin:22px 0}.card{background:#10231d;border:1px solid #24483b;border-radius:10px;padding:16px}.num{font-size:28px;font-weight:700;color:#7de2b8}table{width:100%%;border-collapse:collapse;font-size:12px;background:#0d1d18}th,td{padding:8px;border-bottom:1px solid #244036;text-align:left;vertical-align:top}.pass{color:#9ff3c7;font-weight:700}.warn{color:#ffe19a;font-weight:700}.scroll{overflow:auto}.note{color:#9cb5a9}</style></head><body><main class="wrap"><h1>Forge SVS compatibility audit</h1><p class="note">Every discovered path received TIFF-structure validation, Bio-Formats metadata inspection and a bounded center JPEG tile decode. Source files were not modified.</p><section class="cards"><div class="card"><div class="num">%d</div>paths</div><div class="card"><div class="num">%d</div>opened</div><div class="card"><div class="num">%d</div>truncated</div><div class="card"><div class="num">%d</div>reader failures</div></section><div class="scroll"><table><thead><tr><th>Path</th><th>Bytes</th><th>Same-size paths</th><th>Status</th><th>Dimensions</th><th>Levels</th><th>Tile bytes</th><th>ms</th><th>Detail</th></tr></thead><tbody>%s</tbody></table></div></main></body></html>
                """.formatted(results.size(), opened, corrupt, failed, rows);
        Files.writeString(target, document, StandardCharsets.UTF_8);
    }

    private static String csv(String value) { return '"' + value.replace("\"", "\"\"") + '"'; }
    private static String html(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private record Result(Path path, long bytes, String status, int width, int height,
                          int levels, int tileBytes, long elapsedMs, String detail) { }
}
