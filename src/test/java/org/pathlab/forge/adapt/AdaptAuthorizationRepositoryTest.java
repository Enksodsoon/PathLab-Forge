package org.pathlab.forge.adapt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pathlab.forge.pivot.PivotManifest;
import org.pathlab.forge.pivot.PivotTask;

final class AdaptAuthorizationRepositoryTest {
    @TempDir Path temp;

    @Test
    void persistsExactDatasetViewerAssociationAndRejectsArbitrarySlide() throws Exception {
        var repository = new ViewerSlideAssociationRepository(temp);
        repository.record(new ViewerSlideAssociation(
                "dataset-1", "viewer-1", "a".repeat(64), "Teaching", "CC BY 4.0", "artifact-1"));

        assertEquals("viewer-1", repository.require("dataset-1", "viewer-1").viewerSlideId());
        assertThrows(IllegalArgumentException.class, () -> repository.require("dataset-1", "viewer-2"));
        assertEquals("viewer-1", new ViewerSlideAssociationRepository(temp)
                .require("dataset-1", "viewer-1").viewerSlideId());
    }

    @Test
    void approvalIsDurableAndBoundToTheExactManifestRevision() throws Exception {
        var repository = new PivotApprovalRepository(temp);
        var approved = manifest("revision-1");
        repository.approve(approved, "faculty-42", 1_234L);

        assertEquals("faculty-42", new PivotApprovalRepository(temp)
                .requireApproved(approved).approvedBy());
        assertThrows(IllegalArgumentException.class,
                () -> repository.requireApproved(manifest("revision-2")));
    }

    private static PivotManifest manifest(String revision) {
        return new PivotManifest("pathlab-pivot/v1", "pivot-1", "aabbccddeeff0011", "dataset-1",
                "fingerprint", revision, 0, 100, 100, 1000, 800, 1, 2, 3, 4, 0, 0,
                List.of(new PivotTask("t", "t.jpg", 10, 20, 30, 40, 0, 0, 0, 1, .5, .2, .3)));
    }
}
