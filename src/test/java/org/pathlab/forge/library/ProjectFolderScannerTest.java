package org.pathlab.forge.library;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ProjectFolderScannerTest {
    @TempDir Path temporary;

    @Test
    void recursivelyFindsAllProbeCandidatesButIgnoresKnownCompanionFiles() throws Exception {
        var nested = Files.createDirectories(temporary.resolve("case-a/nested"));
        Files.writeString(temporary.resolve("case-a/slide.vsi"), "vsi");
        Files.writeString(nested.resolve("slide.ets"), "ets");
        Files.writeString(nested.resolve("other.ome.tiff"), "ome");
        Files.writeString(nested.resolve("volume.czi"), "czi");
        Files.writeString(nested.resolve("photo.avif"), "avif");
        Files.writeString(nested.resolve("notes.txt"), "notes");

        var found = ProjectFolderScanner.findSlides(temporary);

        assertEquals(5, found.size());
        assertEquals(
                java.util.Set.of("slide.vsi", "other.ome.tiff", "volume.czi", "photo.avif", "notes.txt"),
                found.stream().map(path -> path.getFileName().toString())
                        .collect(java.util.stream.Collectors.toSet()));
    }
}
