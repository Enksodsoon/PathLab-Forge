package org.pathlab.forge.conversion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class QuPathRuntimeTest {
    @Test
    void buildsBoundedSixCoreLosslessDirectWriterCommand() {
        var request = new ConversionRequest(
                Path.of("slide.vsi"),
                2,
                69_790,
                23_372,
                11_336,
                11_040,
                165_845,
                90_735,
                1.5);

        var command = QuPathRuntime.commandLine(
                Path.of("java.exe"),
                Path.of("qupath", "app"),
                request,
                Path.of("export.partial.ome.tif"));

        assertEquals("java.exe", command.get(0));
        assertTrue(command.contains("-XX:ActiveProcessorCount=6"));
        assertTrue(command.contains("-Xmx4g"));
        assertTrue(command.contains("--compression=UNCOMPRESSED"));
        assertTrue(command.contains("--series=2"));
        assertTrue(command.contains("--crop=69790,23372,11336,11040"));
    }

    @Test
    void rejectsWorkThatCannotFinishInsideSecondsContract() {
        var small = new ConversionRequest(
                Path.of("slide.vsi"), 2, 0, 0, 11_336, 11_040, 11_336, 11_040, 1.5);
        var large = new ConversionRequest(
                Path.of("slide.vsi"),
                2,
                0,
                0,
                103_130,
                75_118,
                103_130,
                75_118,
                1.5);

        QuPathRuntime.requireSecondsBudget(small, true);
        assertThrows(
                IllegalStateException.class,
                () -> QuPathRuntime.requireSecondsBudget(large, true));
    }
}
