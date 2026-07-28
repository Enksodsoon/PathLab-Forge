package org.pathlab.forge.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class BatchIdTest {
    @Test
    void trimsAndPreservesAValidValue() {
        BatchId id = BatchId.of("  batch-001  ");

        assertEquals("batch-001", id.value());
        assertEquals("batch-001", id.toString());
    }

    @Test
    void rejectsBlankAndWhitespaceOnlyValues() {
        assertThrows(IllegalArgumentException.class, () -> BatchId.of(""));
        assertThrows(IllegalArgumentException.class, () -> BatchId.of(" \t "));
    }

    @Test
    void rejectsNullValues() {
        assertThrows(NullPointerException.class, () -> BatchId.of(null));
    }

    @Test
    void comparesByValidatedValue() {
        assertEquals(BatchId.of("batch-001"), BatchId.of(" batch-001 "));
        assertEquals(BatchId.of("batch-001").hashCode(), BatchId.of("batch-001").hashCode());
        assertNotEquals(BatchId.of("batch-001"), BatchId.of("batch-002"));
    }
}
