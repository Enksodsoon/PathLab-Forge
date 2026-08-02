package org.pathlab.forge.adapt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pathlab.forge.pivot.PivotManifest;
import org.pathlab.forge.pivot.PivotTask;
import org.pathlab.forge.conversion.ArtifactRevision;
import org.pathlab.forge.conversion.ArtifactRevisionFormat;
import org.pathlab.forge.conversion.ArtifactRevisionStatus;
import org.pathlab.forge.viewer.ViewerUploadStatus;

final class AdaptAuthorizationRepositoryTest {
    @TempDir Path temp;

    @Test
    void persistsExactDatasetViewerAssociationAndRejectsArbitrarySlide() throws Exception {
        var repository = new ViewerSlideAssociationRepository(temp);
        repository.record(new ViewerSlideAssociation(
                "dataset-1", "viewer-1", "a".repeat(64), "Teaching", "CC BY 4.0", "artifact-1",
                100, 200, 3_000, 2_000, 2, 1_500, 1_000));

        assertEquals("viewer-1", repository.require("dataset-1", "viewer-1").viewerSlideId());
        assertThrows(IllegalArgumentException.class, () -> repository.require("dataset-1", "viewer-2"));
        assertEquals("viewer-1", new ViewerSlideAssociationRepository(temp)
                .require("dataset-1", "viewer-1").viewerSlideId());
        assertEquals(100, repository.require("dataset-1", "viewer-1").cropX());
        assertEquals(1_500, repository.require("dataset-1", "viewer-1").viewerWidth());
        assertEquals(2, repository.require("dataset-1", "viewer-1").downsample());
    }

    @Test
    void preparedAssociationUsesViewerReportedPackageChecksumAndFailsClosed() {
        var omeSha256 = "a".repeat(64);
        var packageSha256 = "b".repeat(64);
        var revision = new ArtifactRevision(
                "artifact-1", "dataset-1", "configuration-1", "source", 1,
                ArtifactRevisionStatus.APPROVED, ArtifactRevisionFormat.PREPARED_DZI_V2,
                "slide.ome.tif", "derivative", "slide.plslide", omeSha256, packageSha256,
                1_000, 800, "ome-dynamic-v1", 75, 1, "Teaching", "");
        var ready = new ViewerUploadStatus(
                "READY_PRIVATE", revision.id(), 100, 100, "viewer-1", packageSha256,
                "PREPARED_V2", "Viewer private slide is ready");

        var association = ViewerSlideAssociation.fromReadyUpload(
                "dataset-1", "Teaching", "institutional-teaching", revision, ready,
                0, 0, 1_000, 800, 1);

        assertEquals(packageSha256, association.sha256());
        assertThrows(IllegalArgumentException.class, () -> ViewerSlideAssociation.fromReadyUpload(
                "dataset-1", "Teaching", "institutional-teaching", revision,
                new ViewerUploadStatus("READY_PRIVATE", revision.id(), 100, 100,
                        "viewer-1", "", "PREPARED_V2", "ready"),
                0, 0, 1_000, 800, 1));
        assertThrows(IllegalArgumentException.class, () -> ViewerSlideAssociation.fromReadyUpload(
                "dataset-1", "Teaching", "institutional-teaching", revision,
                new ViewerUploadStatus("READY_PRIVATE", revision.id(), 100, 100,
                        "viewer-1", omeSha256, "PREPARED_V2", "ready"),
                0, 0, 1_000, 800, 1));
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
