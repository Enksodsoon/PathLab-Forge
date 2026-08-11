package org.pathlab.forge.library;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class ProjectFolderScanner {
    private static final int MAX_DEPTH = 12;
    private static final int MAX_SLIDES = 10_000;

    private ProjectFolderScanner() {}

    public static List<Path> findSlides(Path folder) throws IOException {
        var root = folder.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IOException("Project folder does not exist: " + root);
        }
        try (var paths = Files.walk(root, MAX_DEPTH)) {
            var slides = paths
                    .filter(path -> Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    .filter(ProjectFolderScanner::isPrimarySlide)
                    .sorted(Comparator.comparing(path -> root.relativize(path).toString(),
                            String.CASE_INSENSITIVE_ORDER))
                    .limit(MAX_SLIDES + 1L)
                    .toList();
            if (slides.size() > MAX_SLIDES) {
                throw new IOException("Project folder exceeds the 10,000 slide safety limit");
            }
            return slides;
        }
    }

    private static boolean isPrimarySlide(Path path) {
        var name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".vsi")
                || name.endsWith(".svs")
                || name.endsWith(".ome.tif")
                || name.endsWith(".ome.tiff");
    }
}
