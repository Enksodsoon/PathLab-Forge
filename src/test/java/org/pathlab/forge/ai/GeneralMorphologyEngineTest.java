package org.pathlab.forge.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GeneralMorphologyEngineTest {
    @TempDir Path root;

    @Test void indexesFloat16AndRanksExactCosineDeterministically() throws Exception {
        var engine = new GeneralMorphologyEngine(root);
        var encoder = encoder();
        var patches = List.of(patch(10, 20, "x"), patch(30, 40, "y"));
        var summary = engine.indexSlide(new GeneralMorphologyEngine.IndexRequest("slide-a", "a".repeat(64), GeneralMorphologyEngine.StainProfile.HE, "teacher-1", "fixture", "1", "p1", patches), encoder);
        assertEquals(2, summary.total());
        assertEquals("float16", summary.storageDtype());
        var result = engine.querySimilar(new GeneralMorphologyEngine.QueryRequest(GeneralMorphologyEngine.StainProfile.HE, "teacher-1", patch(10, 20, "x"), 20), encoder);
        assertFalse(result.abstained());
        assertEquals(2, result.matches().size());
        assertEquals(1, result.matches().get(0).rank());
        assertEquals(10, result.matches().get(0).sourceRectangle().x());
        assertFalse(result.matches().get(0).crossStain());
    }

    @Test void unknownAndUnconfirmedStainsFailClosed() {
        var engine = new GeneralMorphologyEngine(root);
        var unknown = engine.abstainUnknown(GeneralMorphologyEngine.StainProfile.UNKNOWN, "Teacher confirmation required");
        assertTrue(unknown.abstained());
        assertThrows(GeneralMorphologyEngine.AbstentionRequiredException.class, () -> engine.indexSlide(new GeneralMorphologyEngine.IndexRequest("slide-a", "a".repeat(64), GeneralMorphologyEngine.StainProfile.PAS, "", "fixture", "1", "p1", List.of(patch(1, 1, "x"))), encoder()));
    }

    @Test void incompatibleSpacesAndPromotionOverFiveAreRejected() throws Exception {
        var engine = new GeneralMorphologyEngine(root);
        engine.indexSlide(new GeneralMorphologyEngine.IndexRequest("slide-a", "a".repeat(64), GeneralMorphologyEngine.StainProfile.HE, "teacher", "fixture", "1", "p1", List.of(patch(1, 1, "x"))), encoder());
        assertThrows(IllegalArgumentException.class, () -> engine.querySimilar(new GeneralMorphologyEngine.QueryRequest(GeneralMorphologyEngine.StainProfile.HE, "teacher", patch(1, 1, "x"), 21), encoder()));
        var match = new GeneralMorphologyEngine.Match(1, "slide-a", "a".repeat(64), new GeneralMorphologyEngine.SourceRectangle(1, 1, 10, 10), 1, false, GeneralMorphologyEngine.EvidenceKind.SIMILAR);
        assertThrows(IllegalArgumentException.class, () -> engine.buildEvidence(List.of(match, match, match, match, match, match)));
        assertTrue(MorphologyModelRouter.catalogue().stream().noneMatch(MorphologyModelRouter.Model::trustRemoteCode));
    }

    private static GeneralMorphologyEngine.PatchSource patch(double x, double y, String name) { return new GeneralMorphologyEngine.PatchSource(new GeneralMorphologyEngine.SourceRectangle(x, y, 224, 224), .5, Path.of(name)); }
    private static GeneralMorphologyEngine.FrozenEncoder encoder() { return new GeneralMorphologyEngine.FrozenEncoder() { public String id() { return "fixture"; } public String version() { return "1"; } public String preprocessingVersion() { return "p1"; } public int dimension() { return 3; } public float[] encode(GeneralMorphologyEngine.PatchSource patch) { return patch.image().toString().equals("x") ? new float[]{1, 0, 0} : new float[]{0, 1, 0}; } }; }
}
