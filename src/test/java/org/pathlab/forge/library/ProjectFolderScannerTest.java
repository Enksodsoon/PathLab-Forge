package org.pathlab.forge.library;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ProjectFolderScannerTest {
    @TempDir Path temporary;

    @Test
    void recursivelyFindsOnlyPrimarySlidesAndIgnoresEtsCompanions() throws Exception {
        var nested = Files.createDirectories(temporary.resolve("case-a/nested"));
        Files.writeString(temporary.resolve("case-a/slide.vsi"), "vsi");
        Files.writeString(nested.resolve("slide.ets"), "ets");
        Files.writeString(nested.resolve("other.ome.tiff"), "ome");
        Files.writeString(nested.resolve("cohort-case.svs"), "svs");
        Files.writeString(nested.resolve("notes.txt"), "notes");

        var found = ProjectFolderScanner.findSlides(temporary);

        assertEquals(3, found.size());
        assertEquals(
                java.util.Set.of("slide.vsi", "other.ome.tiff", "cohort-case.svs"),
                found.stream().map(path -> path.getFileName().toString())
                        .collect(java.util.stream.Collectors.toSet()));
    }
}
