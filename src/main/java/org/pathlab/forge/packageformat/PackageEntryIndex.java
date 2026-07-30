package org.pathlab.forge.packageformat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;

public record PackageEntryIndex(Map<String, Entry> entries) {
    public PackageEntryIndex {
        entries = Map.copyOf(new LinkedHashMap<>(entries));
    }

    public Entry require(String path) {
        var entry = entries.get(path);
        if (entry == null) {
            throw new IllegalArgumentException("Package entry was not indexed: " + path);
        }
        return entry;
    }

    public void write(Path output) throws IOException {
        var content = entries.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "|" + entry.getValue().offset()
                        + "|" + entry.getValue().size())
                .collect(java.util.stream.Collectors.joining("\n", "", "\n"));
        Files.createDirectories(output.toAbsolutePath().normalize().getParent());
        var partial = output.resolveSibling(output.getFileName() + ".partial");
        Files.writeString(partial, content, java.nio.charset.StandardCharsets.UTF_8);
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

    public static PackageEntryIndex read(Path input) throws IOException {
        var values = new LinkedHashMap<String, Entry>();
        for (var line : Files.readAllLines(input, java.nio.charset.StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            var fields = line.split("\\|", 3);
            if (fields.length != 3
                    || fields[0].startsWith("/")
                    || fields[0].contains("\\")
                    || fields[0].contains("../")) {
                throw new IOException("Package index contains an invalid entry");
            }
            try {
                values.put(
                        fields[0],
                        new Entry(Long.parseLong(fields[1]), Long.parseLong(fields[2])));
            } catch (IllegalArgumentException error) {
                throw new IOException("Package index contains an invalid range", error);
            }
        }
        return new PackageEntryIndex(values);
    }

    public record Entry(long offset, long size) {
        public Entry {
            if (offset < 0 || size < 0) {
                throw new IllegalArgumentException("Package entry range is invalid");
            }
        }
    }
}
