package org.pathlab.forge.reader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ReaderRegistryTest {
    @Test
    void preservesMultidimensionalMetadataBeforeChoosingStartupSpeed() throws Exception {
        var fast = candidate("LIBVIPS", "vips", false, false, true, 2);
        var complete = candidate("BIO_FORMATS", "ZeissCZIReader", true, true, false, 40);

        var selected = ReaderRegistry.select(List.of(fast, complete));

        assertEquals("ZeissCZIReader", selected.descriptor().readerId());
    }

    @Test
    void choosesNativePyramidReaderWhenMetadataCapabilitiesAreEquivalent() throws Exception {
        var generic = candidate("BIO_FORMATS", "GenericTiffReader", false, false, false, 30);
        var pyramid = candidate("LIBVIPS", "openslideload", false, false, true, 8);

        var selected = ReaderRegistry.select(List.of(generic, pyramid));

        assertEquals("openslideload", selected.descriptor().readerId());
    }

    @Test
    void rejectsProbeSetWithoutReadableCandidate() {
        assertThrows(ReaderSelectionException.class, () -> ReaderRegistry.select(List.of()));
    }

    private static ReaderCandidate candidate(
            String engine,
            String id,
            boolean multidimensional,
            boolean companions,
            boolean pyramid,
            long startupMillis) {
        return new ReaderCandidate(
                new ReaderDescriptor(
                        engine,
                        id,
                        id,
                        List.of("test"),
                        multidimensional,
                        pyramid,
                        companions,
                        true),
                Path.of("sample.test"),
                "Example",
                List.of(Path.of("sample.test")),
                startupMillis);
    }
}
