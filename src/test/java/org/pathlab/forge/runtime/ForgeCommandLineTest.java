package org.pathlab.forge.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ForgeCommandLineTest {
    @Test
    void parsesIsolatedRootBenchmarkAndPerformanceReport() {
        var command = ForgeCommandLine.parse(new String[] {
            "--serve",
            "--data-root", "C:\\bench\\forge-a",
            "--benchmark", "C:\\slides\\case.vsi",
            "--performance-report", "C:\\reports\\forge.json",
            "--no-browser"
        });

        assertTrue(command.serve());
        assertTrue(command.noBrowser());
        assertEquals(Path.of("C:\\bench\\forge-a"), command.dataRoot());
        assertEquals(Path.of("C:\\slides\\case.vsi"), command.benchmarkSource());
        assertEquals(Path.of("C:\\reports\\forge.json"), command.performanceReport());
    }

    @Test
    void parsesReaderSelfTestSources() {
        var command = ForgeCommandLine.parse(new String[] {
            "--reader-self-test",
            "--data-root", "C:\\clean\\forge",
            "--port", "0",
            "--self-test-source", "E:\\slides\\case.svs",
            "--self-test-source", "E:\\slides\\case.isyntax"
        });

        assertTrue(command.readerSelfTest());
        assertEquals(0, command.port());
        assertEquals(List.of(Path.of("E:\\slides\\case.svs"), Path.of("E:\\slides\\case.isyntax")),
                command.selfTestSources());
    }
}
