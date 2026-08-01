package org.pathlab.forge.pivot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pathlab.forge.conversion.LocalPreview;
import org.pathlab.forge.library.DatasetFormat;
import org.pathlab.forge.library.DatasetStatus;
import org.pathlab.forge.library.LocalDataset;

final class PivotCompilerTest {
    @TempDir
    Path temp;

    @Test
    void compilesDeterministicCoordinateGroundedTasksAndRejectsBlankTiles()
            throws Exception {
        var preview = previewWithTissueTiles();
        var dataset = dataset("fingerprint-one");
        var repository = new PivotRepository(temp.resolve("managed"));
        var compiler = new PivotCompiler(repository, 32, 12);

        var first = compiler.compile(dataset, preview);
        var second = compiler.compile(dataset, preview);

        assertEquals("pathlab-pivot/v1", first.schema());
        assertEquals(first.id(), second.id());
        assertEquals(first.tasks(), second.tasks());
        assertEquals(3, first.tasks().size());
        assertEquals(1, first.rejectedBlank());
        assertFalse(first.tasks().get(0).queryFile().isBlank());
        assertTrue(first.tasks().stream().allMatch(task -> task.targetWidth() > 0));
        assertTrue(first.tasks().stream().allMatch(task -> task.targetHeight() > 0));
        assertTrue(first.tasks().stream().allMatch(task -> task.difficulty() >= 0));
        assertTrue(first.tasks().stream().allMatch(task -> task.difficulty() <= 1));
        for (var task : first.tasks()) {
            assertTrue(Files.isRegularFile(repository.queryImage(dataset.id(), task)));
        }
    }

    @Test
    void sourceIdentityInvalidatesAStoredManifest() throws Exception {
        var preview = previewWithTissueTiles();
        var repository = new PivotRepository(temp.resolve("managed-stale"));
        var compiler = new PivotCompiler(repository, 32, 12);

        compiler.compile(dataset("fingerprint-one"), preview);

        assertTrue(repository.findCurrent(dataset("fingerprint-one")).isPresent());
        assertTrue(repository.findCurrent(dataset("fingerprint-two")).isEmpty());
    }

    @Test
    void packagedDerivativeUsesItsRevisionAndMapsCropCoordinates() throws Exception {
        var preview = previewWithTissueTiles();
        var base = dataset("fingerprint-artifact");
        var dataset = new LocalDataset(
                base.id(), base.displayName(), base.sourcePath(), base.sourceBytes(), base.format(),
                DatasetStatus.PACKAGE_READY, "Review", "", "", 0, 1_280, 1_280, 2,
                0, 100, 200, 256, 256, base.sourceFingerprint(), base.sourceInventory(),
                "cropped-config", "revision-one", "");
        PivotImageSource packaged = new PivotImageSource() {
            public String revision() { return "artifact:revision-one"; }
            public int imageWidth() { return 128; }
            public int imageHeight() { return 128; }
            public double sourceOriginX() { return 100; }
            public double sourceOriginY() { return 200; }
            public double sourceScaleX() { return 2; }
            public double sourceScaleY() { return 2; }
            public byte[] descriptor() throws java.io.IOException {
                return Files.readAllBytes(preview.root().resolve("slide.dzi"));
            }
            public byte[] tile(int level, int x, int y, String format) throws java.io.IOException {
                var file = preview.root().resolve("slide_files").resolve(Integer.toString(level))
                        .resolve(x + "_" + y + "." + format);
                return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
            }
        };
        var repository = new PivotRepository(temp.resolve("managed-artifact"));

        var manifest = new PivotCompiler(repository, 32, 12).compile(dataset, packaged);

        assertEquals("artifact:revision-one", manifest.inputRevision());
        assertTrue(manifest.tasks().stream().allMatch(task -> task.targetX() >= 100));
        assertTrue(manifest.tasks().stream().allMatch(task -> task.targetY() >= 200));
        assertTrue(repository.findCurrent(dataset).isPresent());
        var changedRevision = new LocalDataset(
                dataset.id(), dataset.displayName(), dataset.sourcePath(), dataset.sourceBytes(),
                dataset.format(), dataset.status(), dataset.detail(), dataset.outputPath(),
                dataset.sha256(), dataset.selectedSeries(), dataset.width(), dataset.height(),
                dataset.downsample(), dataset.estimatedOutputBytes(), dataset.cropX(), dataset.cropY(),
                dataset.cropWidth(), dataset.cropHeight(), dataset.sourceFingerprint(),
                dataset.sourceInventory(), dataset.configurationRevision(), "revision-two", "");
        assertTrue(repository.findCurrent(changedRevision).isEmpty());
    }

    @Test
    void acceptsCanonicalMultilineDziWithReorderedSizeAttributes() throws Exception {
        var preview = previewWithTissueTiles();
        Files.writeString(
                preview.root().resolve("slide.dzi"),
                "<?xml version=\"1.0\"?><Image Format=\"jpg\" Overlap=\"1\" "
                        + "TileSize=\"64\"><Size Height=\"128\" Width=\"128\" /></Image>");

        var manifest = new PivotCompiler(
                new PivotRepository(temp.resolve("managed-reordered")), 32, 12)
                .compile(dataset("fingerprint-reordered"), preview);

        assertFalse(manifest.tasks().isEmpty());
    }

    private LocalPreview previewWithTissueTiles() throws Exception {
        var root = Files.createDirectories(temp.resolve("preview"));
        Files.writeString(
                root.resolve("slide.dzi"),
                "<Image TileSize=\"64\" Overlap=\"1\" Format=\"jpg\" "
                        + "xmlns=\"http://schemas.microsoft.com/deepzoom/2008\">"
                        + "<Size Width=\"128\" Height=\"128\"/></Image>");
        var level = Files.createDirectories(root.resolve("slide_files/7"));
        writeTile(level.resolve("0_0.jpg"), true, 1);
        writeTile(level.resolve("1_0.jpg"), true, 2);
        writeTile(level.resolve("0_1.jpg"), true, 3);
        writeTile(level.resolve("1_1.jpg"), false, 4);
        return new LocalPreview(root, 128, 128, 1_280, 1_280);
    }

    private static void writeTile(Path target, boolean tissue, int seed) throws Exception {
        var image = new BufferedImage(65, 65, BufferedImage.TYPE_INT_RGB);
        for (var y = 0; y < image.getHeight(); y++) {
            for (var x = 0; x < image.getWidth(); x++) {
                if (!tissue) {
                    image.setRGB(x, y, Color.WHITE.getRGB());
                    continue;
                }
                var gland = ((x + seed * 7) % 23 < 10) ^ ((y + seed * 3) % 19 < 8);
                var color = gland
                        ? new Color(105 + seed * 4, 61 + seed * 2, 126 + seed * 3)
                        : new Color(225, 174 - seed * 3, 195);
                image.setRGB(x, y, color.getRGB());
            }
        }
        assertTrue(ImageIO.write(image, "jpg", target.toFile()));
    }

    private LocalDataset dataset(String fingerprint) {
        return new LocalDataset(
                "dataset-1",
                "Teaching slide",
                temp.resolve("slide.ome.tif").toString(),
                100,
                DatasetFormat.OME_TIFF,
                DatasetStatus.READY_TO_CONVERT,
                "Ready",
                "",
                "",
                0,
                1_280,
                1_280,
                1,
                0,
                0,
                0,
                1_280,
                1_280,
                fingerprint,
                "inventory",
                "config",
                "",
                "");
    }
}
