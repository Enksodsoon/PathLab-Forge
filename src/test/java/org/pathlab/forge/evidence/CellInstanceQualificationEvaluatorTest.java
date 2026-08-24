package org.pathlab.forge.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CellInstanceQualificationEvaluatorTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temporary;

    @Test
    void evaluatesFourOrganHeldOutCohortAgainstFrozenGates() throws Exception {
        var samples = JSON.createArrayNode();
        for (var organ : new String[] {"lung", "kidney", "breast", "prostate"}) {
            var root = temporary.resolve(organ);
            Files.createDirectories(root);
            var imagePath = root.resolve("image.png");
            var annotationPath = root.resolve("annotation.xml");
            var image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics();
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 64, 64);
            graphics.setColor(new Color(80, 80, 160));
            graphics.fillRect(10, 10, 10, 10);
            graphics.dispose();
            ImageIO.write(image, "png", imagePath.toFile());
            Files.writeString(annotationPath, """
                    <Annotations><Annotation><Regions><Region><Vertices>
                    <Vertex X="10" Y="10"/><Vertex X="20" Y="10"/>
                    <Vertex X="20" Y="20"/><Vertex X="10" Y="20"/>
                    </Vertices></Region></Regions></Annotation></Annotations>
                    """);
            var sample = samples.addObject();
            sample.put("sampleId", organ + "-1");
            sample.put("patientGroup", organ + "-patient");
            sample.put("slideGroup", organ + "-slide");
            sample.put("organ", organ);
            sample.put("split", "qualification-held-out-test");
            sample.put("patientOverlapWithTraining", false);
            sample.put("imagePath", temporary.relativize(imagePath).toString());
            sample.put("imageSha256", sha256(imagePath));
            sample.put("annotationPath", temporary.relativize(annotationPath).toString());
            sample.put("annotationSha256", sha256(annotationPath));
            sample.put("width", 64);
            sample.put("height", 64);
            sample.put("license", "CC-BY-NC-SA-4.0");
            sample.put("permittedUse", "private-research-restricted");
        }
        var cohort = JSON.createObjectNode();
        cohort.put("schema", "pathlab.cell-qualification-cohort/1");
        cohort.put("sampleCount", 4);
        cohort.set("samples", samples);
        cohort.putObject("source").put("integrity", "synthetic-test")
                .put("upstreamChecksumAvailable", false);
        cohort.putObject("gates").put("minimumMacroPq", 0.45)
                .put("minimumInstanceDice", 0.70).put("maximumCountError", 0.15)
                .put("maximumMorphometryBias", 0.10).put("maximumFailedRegionRate", 0.05);
        var manifest = temporary.resolve("cohort.json");
        JSON.writeValue(manifest.toFile(), cohort);

        var metrics = CellInstanceQualificationEvaluator.evaluate(manifest, sha256(manifest));

        assertEquals(CellInstanceQualificationEvaluator.SCHEMA, metrics.path("schema").asText());
        assertEquals(1.0, metrics.path("macroPq").asDouble(), 1e-12);
        assertEquals(1.0, metrics.path("instanceDice").asDouble(), 1e-12);
        assertEquals(0.0, metrics.path("countError").asDouble(), 1e-12);
        assertEquals(0.0, metrics.path("morphometryBias").asDouble(), 1e-12);
        assertEquals(0.0, metrics.path("failedRegionRate").asDouble(), 1e-12);
        assertTrue(metrics.path("deterministicRepeat").asBoolean());
        assertTrue(metrics.path("crossTissuePerformance").asBoolean());
        assertTrue(metrics.path("rightsAndIntegrityPassed").asBoolean());
        assertTrue(metrics.path("resourceCompliant").asBoolean());
        assertTrue(metrics.path("peakHeapMiB").asDouble() < 1024.0);
    }

    private static String sha256(Path path) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path)));
    }
}
