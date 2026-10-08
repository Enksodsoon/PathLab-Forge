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
        var original=draft;
        var preview=service.preview(draft.id(),draft.revision());
        var incompletePreview=preview;
        assertThrows(IllegalStateException.class,()->service.approve(incompletePreview.id(),incompletePreview.revision(),incompletePreview.previewChecksum()));
        for(var task:preview.definition().path("tasks"))preview=service.reviewTask(preview.id(),preview.revision(),preview.previewChecksum(),task.path("id").asText());
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
}
