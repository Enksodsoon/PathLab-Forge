package org.pathlab.forge.annotation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import org.junit.jupiter.api.Test;

final class AnnotationRepositoryTest {
    @Test
    void persistsAndDeletesAnnotations() throws Exception {
        var root = Files.createTempDirectory("forge-annotations");
        var repository = new AnnotationRepository(root);
        var datasetId = "ca38d59a-08ce-44a2-aaf2-cb96bd147bdf";

        var created = repository.create(
                datasetId, "rectangle", "10.5,20;40,80.25", "Tumour", "#FFAA22");

        assertEquals(1, repository.list(datasetId).size());
        assertEquals("#ffaa22", repository.list(datasetId).get(0).color());
        assertTrue(repository.delete(datasetId, created.id()));
        assertTrue(repository.list(datasetId).isEmpty());
    }

    @Test
    void rejectsExecutableOrMalformedGeometry() throws Exception {
        var repository = new AnnotationRepository(Files.createTempDirectory("forge-annotations"));
        assertThrows(
                IllegalArgumentException.class,
                () -> repository.create(
                        "ca38d59a-08ce-44a2-aaf2-cb96bd147bdf",
                        "polygon",
                        "<script>",
                        "",
                        "#ffaa22"));
    }

    @Test
    void scopesAnnotationsToExactPlaneAndMigratesLegacyRecords() throws Exception {
        var repository = new AnnotationRepository(Files.createTempDirectory("forge-annotations"));
        var datasetId = "ca38d59a-08ce-44a2-aaf2-cb96bd147bdf";
        var legacy = repository.create(
                datasetId, "point", "1,2", "Legacy", "#ffaa22");

        repository.scopeLegacy(datasetId, 2, 4, 7, "a".repeat(64));
        var scoped = repository.create(
                datasetId, "point", "3,4", "Plane", "#00ff00",
                2, 5, 7, "b".repeat(64));

        var annotations = repository.list(datasetId);
        assertEquals(2, annotations.size());
        assertEquals(2, annotations.stream().filter(item -> item.series() == 2).count());
        assertEquals("a".repeat(64), annotations.stream()
                .filter(item -> item.id().equals(legacy.id())).findFirst().orElseThrow().viewRevision());
        assertEquals(5, annotations.stream()
                .filter(item -> item.id().equals(scoped.id())).findFirst().orElseThrow().z());
    }
}
