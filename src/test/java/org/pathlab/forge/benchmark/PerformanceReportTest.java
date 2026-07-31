package org.pathlab.forge.benchmark;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PerformanceReportTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void writesMachineReadableHardGateResultsAtomically() throws Exception {
        var output = temporaryDirectory.resolve("report.json");
        var report = new PerformanceReport(
                "slide.vsi",
                "PACKAGE_READY",
                205_000,
                5_400_000_000L,
                1_500_000_000L,
                3_400_000_000L,
                true,
                "",
                java.util.Map.of("DZI_TILES", 400L, "DIRECT_OME", 300L));

        report.write(output);

        var json = Files.readString(output);
        assertTrue(json.contains("\"packageReadyMs\":205000"));
        assertTrue(json.contains("\"hardGatesPassed\":true"));
        assertTrue(json.contains(
                "\"stageDurationsMs\":{\"DIRECT_OME\":300,\"DZI_TILES\":400}"));
        assertTrue(Files.notExists(output.resolveSibling("report.json.partial")));
    }
}
