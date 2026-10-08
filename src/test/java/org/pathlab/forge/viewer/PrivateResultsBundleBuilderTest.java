package org.pathlab.forge.viewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pathlab.forge.annotation.AnnotationRecord;

final class PrivateResultsBundleBuilderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void createsCanonicalBoundedResultSidecarFromAvailableAnnotations() throws Exception {
        var output = temporaryDirectory.resolve("results.plresults");
        var result = new PrivateResultsBundleBuilder().build(
                output,
                "revision-1",
                "a".repeat(64),
                List.of(new AnnotationRecord(
                        "object-1", "rectangle", "10,20;30,50", "Tumour", "#ff0000",
                        1, "", "positive", 2, 3)));

        assertEquals(output, result.path());
        assertEquals(Files.size(output), result.bytes());
        assertEquals(64, result.sha256().length());
        var entries = readTarGz(output);
        assertEquals("pathlab-private-results/v1", text(entries, "manifest.json", "schema"));
        assertTrue(new String(entries.get("objects.ndjson"), StandardCharsets.UTF_8)
                .contains("\"classification\":\"positive\""));
        assertTrue(new String(entries.get("measurements.ndjson"), StandardCharsets.UTF_8)
                .contains("\"name\":\"areaPx2\""));
        assertFalse(entries.keySet().stream().anyMatch(name -> name.contains(":\\") || name.startsWith("/")));
    }

    @Test
    void acceptedDeterministicResultsPreserveScopeTransformGeometryAndStableHash() throws Exception {
        var roi = new AnnotationRecord("roi","rectangle","10,20;30,50","ROI","#ff0000",1,"","",2,3);
        var provenance = new org.pathlab.forge.analysis.AnalysisRun.Provenance("source","inventory","reader","runtime",roi.geometry(),roi.type(),3,0,0,0,"view-1","config","classical","px",java.util.Map.of());
        var run = new org.pathlab.forge.analysis.AnalysisRun("run-1","dataset","roi","nucleus_candidates","SUCCEEDED",1,2,3,"",provenance,java.util.Map.of(),java.util.Map.of("count",2),false);
        var good = new org.pathlab.forge.analysis.PathObject("cell-1","dataset","roi",org.pathlab.forge.analysis.PathObject.Kind.NUCLEUS,"20,30","Reviewed","run-1",java.util.Map.of("accepted","true","geometryType","point"),2);
        var rejected = new org.pathlab.forge.analysis.PathObject("cell-2","dataset","roi",org.pathlab.forge.analysis.PathObject.Kind.NUCLEUS,"21,31","Rejected","run-1",java.util.Map.of("accepted","false"),2);
        var review = new org.pathlab.forge.analysis.AnalysisReview("run-1",1,List.of(rejected,good),List.of());
        var revision = new org.pathlab.forge.conversion.ArtifactRevision("revision","dataset","view-1","source",1,org.pathlab.forge.conversion.ArtifactRevisionStatus.APPROVED,org.pathlab.forge.conversion.ArtifactRevisionFormat.OME_DYNAMIC_V1,"ome","derivative","package","a".repeat(64),"",10,15,"ome-dynamic-v1",75,2,"Export","");
        var accepted = new PrivateResultsBundleBuilder.AcceptedAnalysis(run,review,"view-1");
        var builder = new PrivateResultsBundleBuilder();
        var first = builder.build(temporaryDirectory.resolve("first.plresults"),revision,List.of(roi),List.of(accepted),10,20,20,30,2);
        var second = builder.build(temporaryDirectory.resolve("second.plresults"),revision,List.of(roi),List.of(accepted),10,20,20,30,2);
        assertEquals(first.sha256(),second.sha256());
        var entries = readTarGz(first.path());
        var objects = new String(entries.get("objects.ndjson"),StandardCharsets.UTF_8);
        assertTrue(objects.contains("run-1:cell-1")); assertFalse(objects.contains("run-1:cell-2")); assertTrue(objects.contains("\"x\":5.0"));
        var runs = new String(entries.get("runs.ndjson"),StandardCharsets.UTF_8);
        assertTrue(runs.contains("originalScope")); assertTrue(runs.contains("10,20;30,50"));
        assertTrue(new String(entries.get("reviews.ndjson"),StandardCharsets.UTF_8).contains("cell-2"));
        assertTrue(new String(entries.get("measurements.ndjson"),StandardCharsets.UTF_8).contains("run:run-1:count"));
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,() -> builder.build(temporaryDirectory.resolve("bad.plresults"),revision,List.of(roi),List.of(new PrivateResultsBundleBuilder.AcceptedAnalysis(run,review,"different-view")),10,20,20,30,2));
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,() -> builder.build(temporaryDirectory.resolve("unreviewed.plresults"),revision,List.of(roi),List.of(new PrivateResultsBundleBuilder.AcceptedAnalysis(run,new org.pathlab.forge.analysis.AnalysisReview("run-1",0,List.of(good),List.of()),"view-1")),10,20,20,30,2));
    }

    @Test void preservesCompoundContourMaskInPrivateResultMetadata()throws Exception{
        var geometry=org.pathlab.forge.analysis.MaskContours.compose("rectangle","0,0;10,10","brush_subtract","2,2;8,2;8,8;2,8");
        var output=temporaryDirectory.resolve("mask.plresults");new PrivateResultsBundleBuilder().build(output,"revision","a".repeat(64),List.of(new AnnotationRecord("mask","roi_mask",geometry,"ROI","#ffaa22",1)));
        var entries=readTarGz(output);var object=new com.fasterxml.jackson.databind.ObjectMapper().readTree(new String(entries.get("objects.ndjson"),StandardCharsets.UTF_8));
        assertEquals("evenodd",object.path("geometry").path("fillRule").asText());assertEquals(2,object.path("geometry").path("contours").size());
        assertTrue(new String(entries.get("measurements.ndjson"),StandardCharsets.UTF_8).contains("64.0"));
    }

    private static String text(HashMap<String, byte[]> entries, String name, String key) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(entries.get(name)).get(key).asText();
    }

    private static HashMap<String, byte[]> readTarGz(Path archive) throws Exception {
        var entries = new HashMap<String, byte[]>();
        try (var input = new BufferedInputStream(new GZIPInputStream(Files.newInputStream(archive)))) {
            while (true) {
                var header = input.readNBytes(512);
                if (header.length < 512 || header[0] == 0) {
                    return entries;
                }
                var end = 0;
                while (end < 100 && header[end] != 0) end++;
                var name = new String(header, 0, end, StandardCharsets.UTF_8);
                var size = Long.parseLong(new String(header, 124, 12, StandardCharsets.US_ASCII)
                        .replace("\0", "").trim(), 8);
                entries.put(name, input.readNBytes(Math.toIntExact(size)));
                input.skipNBytes((512 - size % 512) % 512);
            }
        }
    }
}
