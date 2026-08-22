package org.pathlab.forge.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BrightfieldQualificationHarnessTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temporaryDirectory;

    @Test
    void writesChecksumBoundSyntheticQualificationReportAtomically() throws Exception {
        var output = temporaryDirectory.resolve("qualification.json");
        var generatedAt = Instant.parse("2026-08-22T09:00:00Z");

        BrightfieldQualificationHarness.run(
                Path.of("src/main/resources/evidence-packs"), output, generatedAt);

        assertTrue(Files.isRegularFile(output));
        assertFalse(Files.exists(output.resolveSibling("qualification.json.partial")));
        var report = JSON.readTree(output.toFile());
        assertEquals("pathlab.model-qualification-report/1", report.path("schema").asText());
        assertEquals(generatedAt.toString(), report.path("generatedAt").asText());
        assertTrue(report.path("syntheticOnly").asBoolean());
        assertTrue(report.path("researchOnly").asBoolean());
        assertTrue(report.path("notDiagnostic").asBoolean());
        assertEquals("experimental", report.path("overallStatus").asText());
        assertEquals(2, report.path("tracks").size());
        assertFalse(report.toString().contains(temporaryDirectory.toString()));

        var cell = track(report, "cell-morphology");
        assertEquals("cell-od-watershed-v1", cell.path("packId").asText());
        assertEquals(64, cell.path("packManifestSha256").asText().length());
        assertEquals("experimental", cell.path("status").asText());
        assertEquals("fail", check(cell, "touching-nuclei-separation").path("outcome").asText());
        assertEquals(1, check(cell, "touching-nuclei-separation").path("observed").asInt());
        assertEquals(2, check(cell, "touching-nuclei-separation").path("required").asInt());

        var ihc = track(report, "ihc-descriptive");
        assertEquals("ihc-descriptive-v1", ihc.path("packId").asText());
        assertEquals("experimental", ihc.path("status").asText());
        assertEquals("pass", check(ihc, "generic-dab-area").path("outcome").asText());
        assertEquals("pass", check(ihc, "weak-separation-refusal").path("outcome").asText());
        assertEquals("not_evaluable", check(ihc, "marker-specific-measurement")
                .path("outcome").asText());
    }

    @Test
    void isDeterministicForTheSamePacksAndTimestamp() throws Exception {
        var first = temporaryDirectory.resolve("first.json");
        var second = temporaryDirectory.resolve("second.json");
        var generatedAt = Instant.parse("2026-08-22T09:00:00Z");

        BrightfieldQualificationHarness.run(
                Path.of("src/main/resources/evidence-packs"), first, generatedAt);
        BrightfieldQualificationHarness.run(
                Path.of("src/main/resources/evidence-packs"), second, generatedAt);

        assertEquals(Files.readString(first), Files.readString(second));
    }

    private static com.fasterxml.jackson.databind.JsonNode track(
            com.fasterxml.jackson.databind.JsonNode report, String capability) {
        for (var track : report.path("tracks")) {
            if (capability.equals(track.path("capability").asText())) return track;
        }
        throw new AssertionError("Missing track: " + capability);
    }

    private static com.fasterxml.jackson.databind.JsonNode check(
            com.fasterxml.jackson.databind.JsonNode track, String id) {
        for (var check : track.path("checks")) {
            if (id.equals(check.path("id").asText())) return check;
        }
        throw new AssertionError("Missing check: " + id);
    }
}
