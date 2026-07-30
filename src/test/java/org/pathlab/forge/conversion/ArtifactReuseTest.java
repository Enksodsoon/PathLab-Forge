package org.pathlab.forge.conversion;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pathlab.forge.library.DatasetFormat;
import org.pathlab.forge.library.DatasetStatus;
import org.pathlab.forge.library.LocalDataset;

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
                ome.toString(),
                derivative.toString(),
                preparedPackage.toString(),
                sha256(ome),
                sha256(preparedPackage),
                request.outputWidth(),
                request.outputHeight(),
                0,
                "");

        assertTrue(ConversionService.isReusableArtifact(dataset, request, revision));
        assertTrue(Files.isRegularFile(root.resolve("artifact.integrity.properties")));

        Files.writeString(root.resolve("artifact.integrity.properties"), "omeSize=invalid");
        assertTrue(ConversionService.isReusableArtifact(dataset, request, revision));

        Files.writeString(ome, "mutated");
        assertFalse(ConversionService.isReusableArtifact(dataset, request, revision));
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
