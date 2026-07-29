package org.pathlab.forge.packageformat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public final class PreparedPackageBuilder {
    private static final int BLOCK = 512;

    private PreparedPackageBuilder() {}

    public static PackageInfo build(
            Path derivativeRoot,
            int width,
            int height,
            PackageMetadata metadata,
            Path output)
            throws IOException {
        var root = derivativeRoot.toAbsolutePath().normalize();
        var payloads = new ArrayList<Payload>();
        try (var paths = Files.walk(root)) {
            for (var path : paths.filter(Files::isRegularFile).sorted().toList()) {
                if (Files.isSymbolicLink(path)
                        || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Package payload contains a link or special file");
                }
                var relative = root.relativize(path).toString().replace('\\', '/');
                if (!relative.equals("slide.dzi")
                        && !relative.equals("thumbnail.jpg")
                        && !relative.matches("slide_files/\\d+/\\d+_\\d+\\.jpg")) {
                    throw new IOException("Unexpected package payload: " + relative);
                }
                payloads.add(new Payload(
                        "derivative/" + relative,
                        path,
                        Files.size(path),
                        sha256(path)));
            }
        }
        if (payloads.size() < 3) {
            throw new IOException("Derivative is incomplete");
        }
        var manifest = manifest(width, height, metadata, payloads)
                .getBytes(StandardCharsets.UTF_8);
        var manifestHash = HexFormat.of().formatHex(sha256Digest().digest(manifest))
                .getBytes(StandardCharsets.US_ASCII);
        var partial = output.resolveSibling(output.getFileName() + ".partial");
        Files.createDirectories(output.toAbsolutePath().normalize().getParent());
        try {
            try (OutputStream stream = Files.newOutputStream(partial)) {
                writeEntry(stream, "manifest.json", manifest.length, new java.io.ByteArrayInputStream(manifest));
                writeEntry(
                        stream,
                        "manifest.sha256",
                        manifestHash.length,
                        new java.io.ByteArrayInputStream(manifestHash));
                for (var payload : payloads) {
                    try (InputStream input = Files.newInputStream(payload.path())) {
                        writeEntry(stream, payload.name(), payload.bytes(), input);
                    }
                }
                stream.write(new byte[BLOCK * 2]);
            }
            atomicReplace(partial, output);
        } finally {
            Files.deleteIfExists(partial);
        }
        var derivativeBytes = payloads.stream()
                .mapToLong(Payload::bytes)
                .reduce(0, Math::addExact);
        return new PackageInfo(
                output.toAbsolutePath().normalize(),
                Files.size(output),
                sha256(output),
                derivativeBytes,
                payloads.size());
    }

    private static String manifest(
            int width, int height, PackageMetadata metadata, List<Payload> payloads) {
        var files = payloads.stream()
                .map(payload -> "{\"path\":\"" + payload.name()
                        + "\",\"size\":" + payload.bytes()
                        + ",\"sha256\":\"" + payload.sha256() + "\"}")
                .collect(java.util.stream.Collectors.joining(","));
        return "{\"schema\":\"pathlab-prepared-slide/v2\",\"producer\":{"
                + "\"name\":\"PathLab Forge\",\"version\":\""
                + escape(metadata.producerVersion()) + "\"},\"provenance\":{"
                + "\"artifactRevisionId\":\"" + escape(metadata.artifactRevisionId()) + "\","
                + "\"configurationRevision\":\"" + escape(metadata.configurationRevision()) + "\","
                + "\"sourceFingerprint\":\"" + escape(metadata.sourceFingerprint()) + "\","
                + "\"series\":" + metadata.series() + ",\"crop\":{"
                + "\"x\":" + metadata.cropX() + ",\"y\":" + metadata.cropY()
                + ",\"width\":" + metadata.cropWidth()
                + ",\"height\":" + metadata.cropHeight() + "},"
                + "\"downsample\":" + metadata.downsample() + ","
                + "\"coordinateTransform\":{\"translateX\":" + -metadata.cropX()
                + ",\"translateY\":" + -metadata.cropY()
                + ",\"scale\":" + (1.0 / metadata.downsample()) + "},"
                + "\"calibration\":{\"pixelSizeX\":" + metadata.physicalSizeX()
                        * metadata.downsample()
                + ",\"pixelSizeY\":" + metadata.physicalSizeY()
                        * metadata.downsample()
                + ",\"unit\":\"" + escape(metadata.physicalUnit()) + "\"}},\"slide\":{"
                + "\"width\":" + width + ",\"height\":" + height
                + ",\"tileSize\":512,\"overlap\":1,\"format\":\"jpg\"},"
                + "\"files\":[" + files + "]}";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void writeEntry(
            OutputStream output, String name, long size, InputStream input) throws IOException {
        var nameBytes = name.getBytes(StandardCharsets.UTF_8);
        if (nameBytes.length > 100) {
            throw new IOException("Package path exceeds USTAR name limit");
        }
        var header = new byte[BLOCK];
        System.arraycopy(nameBytes, 0, header, 0, nameBytes.length);
        writeOctal(header, 100, 8, 0644);
        writeOctal(header, 108, 8, 0);
        writeOctal(header, 116, 8, 0);
        writeOctal(header, 124, 12, size);
        writeOctal(header, 136, 12, 0);
        java.util.Arrays.fill(header, 148, 156, (byte) ' ');
        header[156] = '0';
        System.arraycopy("ustar\0".getBytes(StandardCharsets.US_ASCII), 0, header, 257, 6);
        System.arraycopy("00".getBytes(StandardCharsets.US_ASCII), 0, header, 263, 2);
        var checksum = 0;
        for (var value : header) {
            checksum += value & 0xff;
        }
        var checksumText = String.format(java.util.Locale.ROOT, "%06o", checksum)
                .getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(checksumText, 0, header, 148, checksumText.length);
        header[154] = 0;
        header[155] = ' ';
        output.write(header);
        input.transferTo(new BoundedOutputStream(output, size));
        var padding = (int) ((BLOCK - size % BLOCK) % BLOCK);
        if (padding > 0) {
            output.write(new byte[padding]);
        }
    }

    private static void writeOctal(byte[] header, int offset, int length, long value) {
        var text = String.format(java.util.Locale.ROOT, "%0" + (length - 1) + "o", value)
                .getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(text, 0, header, offset, text.length);
        header[offset + length - 1] = 0;
    }

    private static String sha256(Path path) throws IOException {
        var digest = sha256Digest();
        try (InputStream input = Files.newInputStream(path)) {
            input.transferTo(new java.security.DigestOutputStream(
                    OutputStream.nullOutputStream(), digest));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static void atomicReplace(Path partial, Path output) throws IOException {
        try {
            Files.move(
                    partial,
                    output,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(partial, output, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private record Payload(String name, Path path, long bytes, String sha256) {}

    private static final class BoundedOutputStream extends OutputStream {
        private final OutputStream delegate;
        private long remaining;

        private BoundedOutputStream(OutputStream delegate, long remaining) {
            this.delegate = delegate;
            this.remaining = remaining;
        }

        @Override
        public void write(int value) throws IOException {
            if (remaining <= 0) {
                throw new IOException("Payload exceeds declared size");
            }
            delegate.write(value);
            remaining--;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            if (length > remaining) {
                throw new IOException("Payload exceeds declared size");
            }
            delegate.write(bytes, offset, length);
            remaining -= length;
        }
    }
}
