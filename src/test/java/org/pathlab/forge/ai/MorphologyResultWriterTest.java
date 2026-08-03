package org.pathlab.forge.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MorphologyResultWriterTest {
    @TempDir Path root;

    @Test void writesSignedDiagnosisFreeV2Manifest() throws Exception {
        var encoder = new GeneralMorphologyEngine.FrozenEncoder() {
            public String id() { return "kaiko"; } public String version() { return "pinned-1"; }
            public String preprocessingVersion() { return "prep-1"; } public int dimension() { return 3; }
            public float[] encode(GeneralMorphologyEngine.PatchSource patch) { return new float[] {1, 0, 0}; }
        };
        var rectangle = new GeneralMorphologyEngine.SourceRectangle(10, 20, 224, 224);
        var match = new GeneralMorphologyEngine.Match(1, "slide-2", "b".repeat(64), rectangle, .91, false, GeneralMorphologyEngine.EvidenceKind.SIMILAR);
        var result = new GeneralMorphologyEngine.QueryResult("kaiko@pinned-1/prep-1/he", false, null, List.of(match));
        var output = root.resolve("result.json");
        new MorphologyResultWriter(root).writeQuery(output, "job-1", "a".repeat(64), "fixture", encoder, GeneralMorphologyEngine.StainProfile.HE, "teacher-1", .5, rectangle, result, Duration.ofSeconds(1));
        var value = new ObjectMapper().readTree(output.toFile());
        assertEquals("pathlab-ai-result/v2", value.get("ai_lab_schema").asText());
        assertFalse(value.get("contains_diagnosis").asBoolean());
        assertFalse(value.get("official_score_impact").asBoolean());
        assertEquals("Ed25519", value.at("/signature/algorithm").asText());
        assertEquals("float16", value.at("/embedding/storage_dtype").asText());
        assertEquals(1, value.get("matches").size());
    }
}
