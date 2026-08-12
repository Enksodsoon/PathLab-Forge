package org.pathlab.forge.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class JobIdTest {
    @Test
    void trimsAndPreservesAValidValue() {
        JobId id = JobId.of("  job-001  ");

        assertEquals("job-001", id.value());
        assertEquals("job-001", id.toString());
    }

    @Test
    void rejectsBlankAndWhitespaceOnlyValues() {
        assertThrows(IllegalArgumentException.class, () -> JobId.of(""));
        assertThrows(IllegalArgumentException.class, () -> JobId.of("\r\n"));
    }

    @Test
    void rejectsNullValues() {
        assertThrows(NullPointerException.class, () -> JobId.of(null));
    }

    @Test
    void comparesByValidatedValue() {
        assertEquals(JobId.of("job-001"), JobId.of(" job-001 "));
        assertEquals(JobId.of("job-001").hashCode(), JobId.of("job-001").hashCode());
        assertNotEquals(JobId.of("job-001"), JobId.of("job-002"));
    }
}
