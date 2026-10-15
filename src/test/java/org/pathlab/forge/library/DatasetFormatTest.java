package org.pathlab.forge.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

final class DatasetFormatTest {
    @Test
    void retainsArbitraryReaderDetectedFormatNames() {
        var format = DatasetFormat.valueOf("ZEISS_CZI");

        assertEquals("ZEISS_CZI", format.name());
        assertFalse(format.isSingleFileTiff());
    }
}
