package org.pathlab.forge.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

public record PerformanceReport(
        String source,
        String status,
        long packageReadyMs,
        long peakProcessTreeBytes,
        long retainedArtifactBytes,
        long peakWorkspaceBytes,
        boolean hardGatesPassed,
        String failure) {
    public PerformanceReport {
        source = Objects.requireNonNull(source, "source");
        status = Objects.requireNonNull(status, "status");
        failure = Objects.requireNonNull(failure, "failure");
        if (packageReadyMs < 0
                || peakProcessTreeBytes < 0
                || retainedArtifactBytes < 0
                || peakWorkspaceBytes < 0) {
            throw new IllegalArgumentException("Performance measurements cannot be negative");
        }
    }

    public void write(Path output) throws IOException {
        var target = output.toAbsolutePath().normalize();
        var parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        var partial = target.resolveSibling(target.getFileName() + ".partial");
        Files.writeString(partial, toJson(), StandardCharsets.UTF_8);
        try {
            Files.move(
                    partial,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public String toJson() {
        return "{"
                + "\"source\":\"" + escape(source) + "\","
                + "\"status\":\"" + escape(status) + "\","
                + "\"packageReadyMs\":" + packageReadyMs + ","
                + "\"peakProcessTreeBytes\":" + peakProcessTreeBytes + ","
                + "\"retainedArtifactBytes\":" + retainedArtifactBytes + ","
                + "\"peakWorkspaceBytes\":" + peakWorkspaceBytes + ","
                + "\"hardGatesPassed\":" + hardGatesPassed + ","
                + "\"failure\":\"" + escape(failure) + "\""
                + "}\n";
    }

    private static String escape(String value) {
        var escaped = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
