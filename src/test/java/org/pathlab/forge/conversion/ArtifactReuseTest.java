package org.pathlab.forge.conversion;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pathlab.forge.derivative.DerivativeEngine;
import org.pathlab.forge.derivative.DerivativeInfo;
import org.pathlab.forge.derivative.OmeDynamicProfile;
import org.pathlab.forge.library.DatasetFormat;
import org.pathlab.forge.library.DatasetStatus;
import org.pathlab.forge.library.LocalDataset;
import org.pathlab.forge.library.PropertiesDatasetRepository;

class ArtifactReuseTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void reusesOnlyAnExactHashVerifiedArtifactForTheCurrentConfiguration() throws Exception {
        var dataset = configured();
        var request = new ConversionRequest(
                Path.of(dataset.sourcePath()),
                dataset.selectedSeries(),
                dataset.cropX(),
                dataset.cropY(),
                dataset.cropWidth(),
                dataset.cropHeight(),
                dataset.width(),
                dataset.height(),
                dataset.downsample());
        var root = temporaryDirectory.resolve("artifact");
        var derivative = root.resolve("derivative");
        Files.createDirectories(root);
        var ome = Files.writeString(root.resolve("export.ome.tif"), "verified ome");
        var preparedPackage = Files.writeString(root.resolve("slide.plslide"), "verified package");
        Files.writeString(root.resolve("slide.plslide.index"), "entry index");
        var revision = new ArtifactRevision(
                "11111111-1111-1111-1111-111111111111",
                dataset.id(),
                dataset.configurationRevision(),
                dataset.sourceFingerprint(),
                System.currentTimeMillis(),
                ArtifactRevisionStatus.READY,
                ArtifactRevisionFormat.PREPARED_DZI_V2,
                ome.toString(),
                derivative.toString(),
                preparedPackage.toString(),
                sha256(ome),
                sha256(preparedPackage),
                request.outputWidth(),
                request.outputHeight(),
                "ome-dynamic-v1",
                75,
                0,
                "Test conversion",
                "");

        assertTrue(ConversionService.isReusableArtifact(dataset, request, revision));
        assertTrue(Files.isRegularFile(root.resolve("artifact.integrity.properties")));

        Files.writeString(root.resolve("artifact.integrity.properties"), "omeSize=invalid");
        assertTrue(ConversionService.isReusableArtifact(dataset, request, revision));

        Files.delete(ome);
        assertTrue(ConversionService.isReusableArtifact(dataset, request, revision));

        Files.writeString(preparedPackage, "mutated");
        assertFalse(ConversionService.isReusableArtifact(dataset, request, revision));
    }

    @Test
    void reusesAValidatedDirectOmeWithoutACompatibilityPackage() throws Exception {
        var dataset = configured();
        var request = new ConversionRequest(
                Path.of(dataset.sourcePath()), 3, 0, 0, 72_792, 66_004,
                72_792, 66_004, 1.5);
        var root = temporaryDirectory.resolve("direct-artifact");
        Files.createDirectories(root);
        var ome = Files.writeString(root.resolve("export.ome.tif"), "verified direct ome");
        var revision = new ArtifactRevision(
                "22222222-2222-2222-2222-222222222222",
                dataset.id(), dataset.configurationRevision(), dataset.sourceFingerprint(),
                System.currentTimeMillis(), ArtifactRevisionStatus.READY,
                ArtifactRevisionFormat.OME_DYNAMIC_V1,
                ome.toString(), root.resolve("derivative").toString(),
                root.resolve("absent.plslide").toString(), sha256(ome), "",
                request.outputWidth(), request.outputHeight(), "ome-dynamic-v1", 75, 0,
                "Direct conversion", "");

        assertTrue(ConversionService.isReusableArtifact(dataset, request, revision));
        assertTrue(ArtifactIntegrityStamp.matchesOme(revision));
        Files.writeString(ome, "mutated");
        assertFalse(ConversionService.isReusableArtifact(dataset, request, revision));
    }

    @Test
    void restoresTheDatasetApprovalPointerWhenReusingAnApprovedArtifact() throws Exception {
        var dataset = configured();
        var revisionId = "33333333-3333-3333-3333-333333333333";
        var revision = new ArtifactRevision(
                revisionId,
                dataset.id(), dataset.configurationRevision(), dataset.sourceFingerprint(),
                System.currentTimeMillis(), ArtifactRevisionStatus.READY,
                ArtifactRevisionFormat.OME_DYNAMIC_V1,
                temporaryDirectory.resolve("export.ome.tif").toString(),
                temporaryDirectory.resolve("derivative").toString(),
                temporaryDirectory.resolve("absent.plslide").toString(), "a".repeat(64), "",
                48_528, 44_002, "ome-dynamic-v1", 75, 0, "Direct conversion", "");
        var cached = dataset.withArtifactRevision(
                DatasetStatus.PACKAGE_READY, "cache hit", revision.omePath(),
                revision.omeSha256(), revisionId);

        var restored = ConversionService.restoreReusableApproval(
                cached, revision.approved(System.currentTimeMillis()));

        assertEquals(revisionId, restored.currentArtifactRevision());
        assertEquals(revisionId, restored.approvedArtifactRevision());
    }

    @Test
    void rejectsAHashValidDirectOmeWhenItsViewerPyramidProfileIsIncomplete() throws Exception {
        var repository = new PropertiesDatasetRepository(temporaryDirectory.resolve("library.properties"));
        ConversionEngine engine = new ConversionEngine() {
            @Override public boolean available() { return true; }
            @Override public String runtimeDescription() { return "reuse profile test"; }
            @Override public List<SeriesInfo> inspect(Path source) { return List.of(); }
            @Override public void convert(Path source, int series, Path output) { }
        };
        DerivativeEngine derivatives = new DerivativeEngine() {
            @Override public boolean available() { return true; }
            @Override public String description() { return "incomplete pyramid fixture"; }
            @Override public void validateOmeProfile(
                    Path ome, int width, int height, OmeDynamicProfile profile, int quality)
                    throws java.io.IOException {
                throw new java.io.IOException("Dynamic OME pyramid level count is invalid");
            }
            @Override public void optimizeOme(Path input, Path output, int width, int height) { }
            @Override public DerivativeInfo generateDzi(
                    Path input, Path output, int width, int height) {
                throw new AssertionError("DZI generation is not used");
            }
        };
        var revision = new ArtifactRevision(
                "44444444-4444-4444-4444-444444444444", "dataset", "config", "a".repeat(64),
                System.currentTimeMillis(), ArtifactRevisionStatus.READY,
                ArtifactRevisionFormat.OME_DYNAMIC_V1,
                temporaryDirectory.resolve("export.ome.tif").toString(),
                temporaryDirectory.resolve("derivative").toString(),
                temporaryDirectory.resolve("absent.plslide").toString(), "b".repeat(64), "",
                11_423, 7_822, "ome-dynamic-v1", 75, 0, "Direct conversion", "");
        var request = new ConversionRequest(Path.of("source.svs"), 0, 0, 0,
                17_135, 11_733, 17_135, 11_733, 1.5);

        try (var service = new ConversionService(
                repository, engine, derivatives, temporaryDirectory.resolve("managed"))) {
            assertFalse(service.validatesReusableProfile(revision, request));
        }
    }

    private static LocalDataset configured() {
        return new LocalDataset(
                        "dataset-reuse",
                        "case.vsi",
                        "C:\\slides\\case.vsi",
                        100,
                        DatasetFormat.VSI,
                        DatasetStatus.READER_REQUIRED,
                        "ready",
                        "",
                        "",
                        -1,
                        0,
                        0,
                        1.0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        "a".repeat(64),
                        "case.vsi|100|0|" + "b".repeat(64),
                        "",
                        "",
                        "")
                .withExportConfiguration(
                        DatasetStatus.READY_TO_CONVERT,
                        "configured",
                        3,
                        72_792,
                        66_004,
                        1.5,
                        1,
                        0,
                        0,
                        72_792,
                        66_004);
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
