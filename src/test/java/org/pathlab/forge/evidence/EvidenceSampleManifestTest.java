package org.pathlab.forge.evidence;

import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class EvidenceSampleManifestTest {
    @TempDir Path temporaryDirectory;
    @Test void recordsGrandfatheredReadOnlySourceWithCompleteProvenance() throws Exception {
        var path = temporaryDirectory.resolve("sample.json");
        Files.writeString(path, "{\"schema\":\"pathlab.evidence-sample/1\","
                + "\"source\":\"BRACS\",\"patientGroup\":\"patient-001\",\"slideId\":\"slide-001\","
                + "\"sha256\":\"" + "a".repeat(64) + "\",\"license\":\"research terms reviewed\","
                + "\"taskLabel\":\"breast-benign\",\"permittedUse\":\"private-research\","
                + "\"bytes\":1234,\"grandfatheredReadOnly\":true}");
        assertTrue(EvidenceSampleManifest.load(path).grandfatheredReadOnly());
    }
}
