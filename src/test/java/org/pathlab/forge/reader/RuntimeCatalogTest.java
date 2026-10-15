package org.pathlab.forge.reader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class RuntimeCatalogTest {
    @Test
    void parsesReadableFormatsAndNormalizesExtensions() throws Exception {
        var xml = """
                <response>
                  <format name='Zeiss CZI'>
                    <tag name='support' value='reading'/>
                    <tag name='extensions' value='czi|CZI'/>
                  </format>
                  <format name='Write only'>
                    <tag name='support' value='writing'/>
                    <tag name='extensions' value='x'/>
                  </format>
                </response>
                """;

        var catalog = RuntimeCatalog.parseBioFormats(xml, "Bio-Formats 8.5.0");

        assertEquals(1, catalog.formats().size());
        assertEquals("Zeiss CZI", catalog.formats().get(0).displayName());
        assertEquals(java.util.List.of("czi"), catalog.formats().get(0).extensions());
        assertTrue(catalog.fingerprint().matches("[0-9a-f]{64}"));
    }

    @Test
    void fingerprintChangesWhenReaderCatalogChanges() throws Exception {
        var first = RuntimeCatalog.parseBioFormats(format("JPEG", "jpg|jpeg"), "8.5.0");
        var second = RuntimeCatalog.parseBioFormats(format("PNG", "png"), "8.5.0");

        assertNotEquals(first.fingerprint(), second.fingerprint());
    }

    private static String format(String name, String extensions) {
        return "<response><format name='" + name + "'>"
                + "<tag name='support' value='reading'/>"
                + "<tag name='extensions' value='" + extensions + "'/>"
                + "</format></response>";
    }
}
