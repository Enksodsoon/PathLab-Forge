package org.pathlab.forge.conversion;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.pathlab.forge.packageformat.PackageEntryIndex;
import org.pathlab.forge.study.TeachingSlideAssociation;

final class TeachingSlideAssociationTest {
    @Test void immutableManifestBindingSurvivesReloadAndRejectsWrongHashOrModifiedPackage() throws Exception {
        var root = Files.createTempDirectory("teaching-binding");
        var datasetId = "ca38d59a-08ce-44a2-aaf2-cb96bd147bdf";
        var revisionId = "fcafc4bf-2350-470c-aa3a-ad5f5a0d9734";
        var directory = root.resolve(datasetId).resolve("artifacts").resolve(revisionId); Files.createDirectories(directory);
        // Tiny synthetic indexed manifest fixture, not a real slide or native-reader qualification.
        var manifest = ("{\"schema\":\"pathlab-prepared-slide/v2\",\"provenance\":{\"artifactRevisionId\":\"" + revisionId
                + "\",\"configurationRevision\":\"config\",\"sourceFingerprint\":\"source\",\"series\":2,"
                + "\"crop\":{\"x\":10,\"y\":20,\"width\":200,\"height\":100},\"downsample\":2,\"viewDefinition\":null},"
                + "\"slide\":{\"width\":100,\"height\":50}}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var packageFile = directory.resolve("slide.plslide"); Files.write(packageFile, manifest);
        new PackageEntryIndex(Map.of("manifest.json", new PackageEntryIndex.Entry(0, manifest.length)))
                .write(packageFile.resolveSibling("slide.plslide.index"));
        var sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(manifest));
        var revision = new ArtifactRevision(revisionId, datasetId, "config", "source", 1, ArtifactRevisionStatus.READY,
                ArtifactRevisionFormat.PREPARED_DZI_V2, directory.resolve("missing.ome.tif").toString(), directory.resolve("derivative").toString(),
                packageFile.toString(), "", sha, 100, 50, "", 0, 0, "Synthetic", "");
        var repository = new ArtifactRevisionRepository(root); repository.save(revision); ArtifactIntegrityStamp.write(revision);
        var association = TeachingSlideAssociation.fromArtifact("local:" + datasetId, "", revision);
        assertEquals(10, association.provenance().path("crop").path("x").asInt());
        assertEquals("config", association.provenance().path("viewRevision").asText());
        association.validate(new ArtifactRevisionRepository(root).find(datasetId, revisionId).orElseThrow());
        var wrong = new TeachingSlideAssociation(association.referenceId(), "", datasetId, revisionId, "a".repeat(64), "config", "source", 100, 50, association.provenance());
        assertThrows(IllegalStateException.class, () -> wrong.validate(revision));
        Files.writeString(packageFile, "changed");
        assertThrows(IllegalStateException.class, () -> association.validate(revision));
    }
}
