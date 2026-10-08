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
        var dzi = "<Image><Size Width=\"100\" Height=\"50\"/></Image>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var tile = new byte[] {1, 2, 3, 4};
        var payload = new java.io.ByteArrayOutputStream(); payload.write(manifest); payload.write(dzi); payload.write(tile);
        var packageFile = directory.resolve("slide.plslide"); Files.write(packageFile, payload.toByteArray());
        var indexFile = packageFile.resolveSibling("slide.plslide.index");
        new PackageEntryIndex(Map.of("manifest.json", new PackageEntryIndex.Entry(0, manifest.length),
                "slide.dzi", new PackageEntryIndex.Entry(manifest.length, dzi.length),
                "slide_files/0/0_0.jpg", new PackageEntryIndex.Entry(manifest.length + dzi.length, tile.length)))
                .write(packageFile.resolveSibling("slide.plslide.index"));
        var sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.toByteArray()));
        var revision = new ArtifactRevision(revisionId, datasetId, "config", "source", 1, ArtifactRevisionStatus.READY,
                ArtifactRevisionFormat.PREPARED_DZI_V2, directory.resolve("missing.ome.tif").toString(), directory.resolve("derivative").toString(),
                packageFile.toString(), "", sha, 100, 50, "", 0, 0, "Synthetic", "");
        var repository = new ArtifactRevisionRepository(root); repository.save(revision); ArtifactIntegrityStamp.write(revision);
        var association = TeachingSlideAssociation.fromArtifact("local:" + datasetId, "", revision);
        assertEquals(10, association.provenance().path("crop").path("x").asInt());
        assertEquals("config", association.provenance().path("viewRevision").asText());
        association.validate(new ArtifactRevisionRepository(root).find(datasetId, revisionId).orElseThrow());
        Files.createDirectories(directory.resolve("derivative/slide_files/0"));
        Files.writeString(directory.resolve("derivative/slide.dzi"), "tampered loose DZI");
        Files.writeString(directory.resolve("derivative/slide_files/0/0_0.jpg"), "tampered loose tile");
        assertArrayEquals(dzi, TeachingSlideAssociation.readPreview(revision, "slide.dzi"));
        assertArrayEquals(tile, TeachingSlideAssociation.readPreview(revision, "slide_files/0/0_0.jpg"));
        for (var path : java.util.List.of("../manifest.json", "/slide.dzi", "manifest.json", "slide_files/../0_0.jpg", "C:\\private")) {
            assertThrows(IllegalArgumentException.class, () -> TeachingSlideAssociation.readPreview(revision, path));
        }
        var originalIndex = Files.readString(indexFile);
        Files.writeString(indexFile, "slide.dzi|9223372036854775807|32\n");
        assertThrows(IllegalStateException.class, () -> TeachingSlideAssociation.readPreview(revision, "slide.dzi"), "Changed index stamp must fail before reading");
        ArtifactIntegrityStamp.write(revision);
        assertThrows(java.io.IOException.class, () -> TeachingSlideAssociation.readPreview(revision, "slide.dzi"), "Range cannot overflow or escape the package");
        Files.writeString(indexFile, "slide.dzi|0|33554433\n"); ArtifactIntegrityStamp.write(revision);
        assertThrows(java.io.IOException.class, () -> TeachingSlideAssociation.readPreview(revision, "slide.dzi"), "Entry allocation is bounded to 32 MiB");
        Files.writeString(indexFile, "x".repeat(513) + "\n"); ArtifactIntegrityStamp.write(revision);
        assertThrows(java.io.IOException.class, () -> TeachingSlideAssociation.readPreview(revision, "slide.dzi"), "A single index line cannot consume unbounded memory");
        Files.writeString(indexFile, originalIndex); ArtifactIntegrityStamp.write(revision);
        var stampFile = directory.resolve("artifact.integrity.properties");
        var stamp = new java.util.Properties(); try (var input = Files.newInputStream(stampFile)) { stamp.load(input); }
        stamp.setProperty("packageSha256", "b".repeat(64)); try (var output = Files.newOutputStream(stampFile)) { stamp.store(output, "Synthetic tampered stamp"); }
        assertThrows(IllegalStateException.class, () -> TeachingSlideAssociation.readPreview(revision, "slide.dzi"));
        ArtifactIntegrityStamp.write(revision);
        var wrong = new TeachingSlideAssociation(association.referenceId(), "", datasetId, revisionId, "a".repeat(64), "config", "source", 100, 50, association.provenance());
        assertThrows(IllegalStateException.class, () -> wrong.validate(revision));
        Files.writeString(packageFile, "changed");
        assertThrows(IllegalStateException.class, () -> association.validate(revision));
    }
}
