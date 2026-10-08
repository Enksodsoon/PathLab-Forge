package org.pathlab.forge.study;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

final class StudyAuthoringServiceTest {
    @Test void incompleteDraftsRemainEditableButMalformedCollectionsAndUnqualifiedScoringAreRejected()throws Exception{
        var service=new StudyAuthoringService(Files.createTempDirectory("study-draft-shape"));
        var blank=service.createDraft("Offline draft","{}","{}");
        assertTrue(blank.definition().path("tasks").isArray());assertFalse(blank.issues().isEmpty());
        assertThrows(IllegalArgumentException.class,()->service.createDraft("Malformed","{\"tasks\":[{\"sources\":[\"unsafe shape\"]}]}","{}"));
        assertThrows(IllegalArgumentException.class,()->StudyAuthoringService.score(StudyPackCanonicalJson.parse("{\"type\":\"faculty-conversion\"}"),StudyPackCanonicalJson.parse("{\"x\":0,\"y\":0}")));
    }
    private static String resource(String name)throws Exception{return new String(StudyAuthoringServiceTest.class.getResourceAsStream("/study/"+name).readAllBytes(),StandardCharsets.UTF_8);}
    @Test void matchesCurrentViewerCanonicalChecksumAndCoordinateScoring()throws Exception{
        var authoritative = StudyPackCanonicalJson.parse(resource("viewer-authoritative-v1.json"));
        assertEquals("fb78ac6fdb525faba001395a6a155ef0fe399d481c1deae7c376311e661f0bd2", StudyPackCanonicalJson.checksum(authoritative));
        StudyAuthoringService.validateApproved(authoritative);
        var approved=StudyPackCanonicalJson.parse(resource("viewer-v1.json"));
        assertEquals(resource("viewer-v1.checksum"),StudyPackCanonicalJson.checksum(approved));
        StudyAuthoringService.validateApproved(approved);
        assertEquals(resource("canonical-numbers.txt"),StudyPackCanonicalJson.canonicalize(StudyPackCanonicalJson.parse(resource("canonical-numbers.json"))));
        var task=approved.path("tasks").get(1);
        assertTrue(StudyAuthoringService.score(task,StudyPackCanonicalJson.parse("{\"x\":0.2,\"y\":0.3}")).correct());
        assertFalse(StudyAuthoringService.score(task,StudyPackCanonicalJson.parse("{\"x\":0.9,\"y\":0.9}")).correct());
    }
    @Test void draftsRecoverAndEveryEditInvalidatesPreviewWhileApprovedVersionsStayImmutable()throws Exception{
        var root=Files.createTempDirectory("study-authoring");var service=new StudyAuthoringService(root);
        var draft=service.createDraft("Named faculty draft",resource("viewer-v1.json"),"{\"slide-1\":{\"datasetId\":\"local-id\"}}");
        for (var slide : draft.definition().path("slides")) draft = service.associateTeachingSlide(draft.id(), draft.revision(), slide.path("viewerSlideId").asText(),
                association(slide.path("viewerSlideId").asText(), slide.path("viewerSlideId").asText(), slide.path("sha256").asText()));
        var original=draft;
        var preview=service.preview(draft.id(),draft.revision());
        var incompletePreview=preview;
        assertThrows(IllegalStateException.class,()->service.approve(incompletePreview.id(),incompletePreview.revision(),incompletePreview.previewChecksum()));
        for(var task:preview.definition().path("tasks")) {
            var binding = service.teachingAssociation(preview.id(), task.path("slideId").asText());
            preview=service.reviewTask(preview.id(),preview.revision(),preview.previewChecksum(),task.path("id").asText(),
                    new StudyAuthoringService.LoadedPixels(preview.previewChecksum(), task.path("slideId").asText(), binding.datasetId(), binding.artifactRevision(), binding.packageSha256()));
        }
        var approved=service.approve(preview.id(),preview.revision(),preview.previewChecksum());
        assertEquals(2,StudyPackCanonicalJson.parse(service.approved(approved.approvedChecksum())).path("tasks").size());
        assertFalse(service.approved(approved.approvedChecksum()).contains("local-id"));
        assertEquals(original.definition(),service.importDraft(service.exportDraft(draft.id())).definition());
        var changed=approved.definition().deepCopy();changed.put("title","Edited faculty title");
        var edited=service.saveDraft(approved.id(),approved.name(),approved.revision(),changed.toString(),approved.associations().toString());
        assertEquals("",edited.previewChecksum());assertEquals("",edited.approvedChecksum());
        assertThrows(IllegalStateException.class,()->service.saveDraft(edited.id(),edited.name(),approved.revision(),changed.toString(),"{}"));
        assertEquals(edited.definition(),new StudyAuthoringService(root).getDraft(edited.id()).definition());
        var recovered=service.recover(edited.id(),original.revision(),edited.revision());assertEquals(original.definition(),recovered.definition());
        var duplicate=service.duplicate(draft.id(),"Version two",true);assertEquals(2,duplicate.definition().path("version").asInt());
    }
    private static TeachingSlideAssociation association(String reference, String viewer, String sha) {
        return new TeachingSlideAssociation(reference, viewer, "ca38d59a-08ce-44a2-aaf2-cb96bd147bdf",
                "fcafc4bf-2350-470c-aa3a-ad5f5a0d9734", sha, "configuration", "source", 100, 100,
                StudyPackCanonicalJson.mapper().createObjectNode());
    }
    @Test void unresolvedOfflineApprovalIsLocalOnlyAndResolutionRequiresNewPreview() throws Exception {
        var root = Files.createTempDirectory("study-offline-binding"); var service = new StudyAuthoringService(root);
        var definition = StudyPackCanonicalJson.parse(resource("viewer-v1.json"));
        var old = definition.path("slides").get(0).path("viewerSlideId").asText();
        var local = "local:ca38d59a-08ce-44a2-aaf2-cb96bd147bdf";
        ((com.fasterxml.jackson.databind.node.ObjectNode) definition.path("slides").get(0)).put("viewerSlideId", local);
        for (var task : definition.path("tasks")) if (old.equals(task.path("slideId").asText())) ((com.fasterxml.jackson.databind.node.ObjectNode) task).put("slideId", local);
        var draft = service.createDraft("Offline", definition.toString(), "{}");
        for (var slide : draft.definition().path("slides")) draft = service.associateTeachingSlide(draft.id(), draft.revision(), slide.path("viewerSlideId").asText(),
                association(slide.path("viewerSlideId").asText(), slide.path("viewerSlideId").asText().startsWith("local:") ? "" : slide.path("viewerSlideId").asText(), slide.path("sha256").asText()));
        var preview = service.preview(draft.id(), draft.revision()); var unreviewed = preview;
        assertThrows(IllegalStateException.class, () -> service.reviewTask(unreviewed.id(), unreviewed.revision(), unreviewed.previewChecksum(), "mcq-1"));
        var firstTask = preview.definition().path("tasks").get(0);
        var firstBinding = service.teachingAssociation(preview.id(), firstTask.path("slideId").asText());
        assertThrows(IllegalStateException.class, () -> service.reviewTask(unreviewed.id(), unreviewed.revision(), unreviewed.previewChecksum(), firstTask.path("id").asText(),
                new StudyAuthoringService.LoadedPixels(unreviewed.previewChecksum(), firstTask.path("slideId").asText(), firstBinding.datasetId(), firstBinding.artifactRevision(), "b".repeat(64))));
        for (var task : preview.definition().path("tasks")) {
            var binding = service.teachingAssociation(preview.id(), task.path("slideId").asText());
            preview = service.reviewTask(preview.id(), preview.revision(), preview.previewChecksum(), task.path("id").asText(),
                    new StudyAuthoringService.LoadedPixels(preview.previewChecksum(), task.path("slideId").asText(), binding.datasetId(), binding.artifactRevision(), binding.packageSha256()));
        }
        var approved = service.approve(preview.id(), preview.revision(), preview.previewChecksum());
        var exported = StudyPackCanonicalJson.parse(service.approved(approved.approvedChecksum()));
        assertEquals("pathlab.study-local-approved/1", exported.path("schema").asText());
        assertThrows(IllegalStateException.class, () -> StudyAuthoringService.validateApproved((com.fasterxml.jackson.databind.node.ObjectNode) exported.path("definition")));
        var resolved = service.associateTeachingSlide(approved.id(), approved.revision(), local, association(local, "actual-delivered-viewer-id", definition.path("slides").get(0).path("sha256").asText()));
        assertEquals("", resolved.previewChecksum()); assertEquals("", resolved.approvedChecksum());
        assertTrue(resolved.reviewedTaskIds().isEmpty());
        assertEquals("actual-delivered-viewer-id", new StudyAuthoringService(root).getDraft(resolved.id()).definition().path("slides").get(0).path("viewerSlideId").asText());
        assertFalse(service.exportDraft(resolved.id()).contains("sourcePath"));
    }
}
