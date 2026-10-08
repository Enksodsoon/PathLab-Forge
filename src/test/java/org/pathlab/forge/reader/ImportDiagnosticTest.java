package org.pathlab.forge.reader;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class ImportDiagnosticTest {
    @Test
    void onlyrepairableFailuresBecomeLibraryCards() {
        assertTrue(ImportDiagnostic.Code.MISSING_COMPANION.repairable());
        assertTrue(ImportDiagnostic.Code.CODEC_UNAVAILABLE.repairable());
        assertTrue(ImportDiagnostic.Code.RESOURCE_LIMIT.repairable());
        assertFalse(ImportDiagnostic.Code.CORRUPT.repairable());
        assertFalse(ImportDiagnostic.Code.UNSUPPORTED.repairable());
        assertFalse(ImportDiagnostic.Code.ENCRYPTED.repairable());
        assertFalse(ImportDiagnostic.Code.PROBE_TIMEOUT.repairable());
    }
}
