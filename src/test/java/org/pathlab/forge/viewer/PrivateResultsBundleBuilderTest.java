package org.pathlab.forge.viewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pathlab.forge.annotation.AnnotationRecord;

final class PrivateResultsBundleBuilderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void createsCanonicalBoundedResultSidecarFromAvailableAnnotations() throws Exception {
        var output = temporaryDirectory.resolve("results.plresults");
        var result = new PrivateResultsBundleBuilder().build(
                output,
                "revision-1",
                "a".repeat(64),
                List.of(new AnnotationRecord(
                        "object-1", "rectangle", "10,20;30,50", "Tumour", "#ff0000",
                        1, "", "positive", 2, 3)));

        assertEquals(output, result.path());
        assertEquals(Files.size(output), result.bytes());
        assertEquals(64, result.sha256().length());
        var entries = readTarGz(output);
        assertEquals("pathlab-private-results/v1", text(entries, "manifest.json", "schema"));
        assertTrue(new String(entries.get("objects.ndjson"), StandardCharsets.UTF_8)
                .contains("\"classification\":\"positive\""));
        assertTrue(new String(entries.get("measurements.ndjson"), StandardCharsets.UTF_8)
                .contains("\"name\":\"areaPx2\""));
        assertFalse(entries.keySet().stream().anyMatch(name -> name.contains(":\\") || name.startsWith("/")));
    }

    @Test
    void includesSignedEvidenceWithoutChangingDeliverySchema() throws Exception {
        var evidence = temporaryDirectory.resolve("evidence.json");
        Files.writeString(evidence, "{\"schema\":\"pathlab.ai-evidence/1\"}");
        var output = temporaryDirectory.resolve("evidence-results.plresults");

        new PrivateResultsBundleBuilder().build(
                output, "revision-1", "a".repeat(64), List.of(), evidence);

        var entries = readTarGz(output);
        assertEquals("pathlab.ai-evidence/1", text(entries, "evidence.json", "schema"));
        assertEquals("pathlab-private-results/v1", text(entries, "manifest.json", "schema"));
    }

    private static String text(HashMap<String, byte[]> entries, String name, String key) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(entries.get(name)).get(key).asText();
    }

    private static HashMap<String, byte[]> readTarGz(Path archive) throws Exception {
        var entries = new HashMap<String, byte[]>();
        try (var input = new BufferedInputStream(new GZIPInputStream(Files.newInputStream(archive)))) {
            while (true) {
                var header = input.readNBytes(512);
                if (header.length < 512 || header[0] == 0) {
                    return entries;
                }
                var end = 0;
                while (end < 100 && header[end] != 0) end++;
                var name = new String(header, 0, end, StandardCharsets.UTF_8);
                var size = Long.parseLong(new String(header, 124, 12, StandardCharsets.US_ASCII)
                        .replace("\0", "").trim(), 8);
                entries.put(name, input.readNBytes(Math.toIntExact(size)));
                input.skipNBytes((512 - size % 512) % 512);
            }
        }
    }
}
