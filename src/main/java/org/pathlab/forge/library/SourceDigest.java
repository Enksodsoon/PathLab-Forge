package org.pathlab.forge.library;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

public record SourceDigest(String fingerprint, String serializedInventory) {
    public SourceDigest {
        if (!fingerprint.matches("[0-9a-f]{64}") || serializedInventory.isBlank()) {
            throw new IllegalArgumentException("Source digest is invalid");
        }
    }

    public static SourceDigest compute(SourceSnapshot snapshot) throws IOException {
        var manifest = new StringBuilder();
        for (var entry : snapshot.files()) {
            manifest.append(entry.relativePath())
                    .append('|')
                    .append(entry.size())
                    .append('|')
                    .append(entry.modifiedAt())
                    .append('|')
                    .append(Base64.getUrlEncoder().withoutPadding().encodeToString(
                            entry.fileId().getBytes(StandardCharsets.UTF_8)))
                    .append('|')
                    .append(sha256(entry.path()))
                    .append('\n');
        }
        var serialized = manifest.toString();
        return new SourceDigest(
                sha256(serialized.getBytes(StandardCharsets.UTF_8)), serialized);
    }

    private static String sha256(java.nio.file.Path path) throws IOException {
        var digest = digest();
        try (InputStream input = java.nio.file.Files.newInputStream(path)) {
            var buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(byte[] value) {
        return HexFormat.of().formatHex(digest().digest(value));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
