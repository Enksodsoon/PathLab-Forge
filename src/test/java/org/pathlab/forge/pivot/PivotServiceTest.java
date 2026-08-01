package org.pathlab.forge.pivot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

final class PivotServiceTest {
    @TempDir
    Path temp;

    @Test
    void scoresFromCoordinatesPersistsProgressAndAdaptsTheNextTask() throws Exception {
        var repository = new PivotRepository(temp.resolve("managed"));
        var compiler = new PivotCompiler(repository, 32, 8);
        var dataset = dataset();
        var manifest = compiler.compile(dataset, preview());
        var service = new PivotService(repository);
        var session = service.start(dataset, manifest);
        var firstTask = service.currentTask(dataset, session).orElseThrow();

        var result = service.submit(
                dataset,
                firstTask.centerX(),
                firstTask.centerY(),
                2_500,
                1_200,
                2,
                3);

        assertEquals(0, result.normalizedError(), 0.000_001);
        assertEquals("MATCH", result.rating());
        assertEquals(1, result.session().completedTasks());
        assertNotEquals(firstTask.id(), result.session().currentTaskId());

        var restored = new PivotService(repository).activeSession(dataset).orElseThrow();
        assertEquals(result.session(), restored);
        assertEquals(1, restored.attempts().size());
        assertEquals(1_200, restored.attempts().get(0).panDistance(), 0.001);
        assertEquals(2, restored.attempts().get(0).zoomReversals());
    }

    @Test
    void hintSkipAndEndRemainAuditable() throws Exception {
        var repository = new PivotRepository(temp.resolve("managed-audit"));
        var compiler = new PivotCompiler(repository, 32, 8);
        var dataset = dataset();
        var manifest = compiler.compile(dataset, preview());
        var service = new PivotService(repository);
        service.start(dataset, manifest);

        var hint = service.hint(dataset);
        assertTrue(hint.text().contains("third"));
        assertEquals(1, hint.session().hintsUsed());

        var skipped = service.skip(dataset);
        assertEquals(1, skipped.skippedTasks());

        var ended = service.end(dataset);
        assertEquals(PivotSessionState.COMPLETED, ended.state());
    }

    private LocalPreview preview() throws Exception {
        var root = Files.createDirectories(temp.resolve("preview"));
        Files.writeString(
                root.resolve("slide.dzi"),
                "<Image TileSize=\"64\" Overlap=\"1\" Format=\"jpg\" "
                        + "xmlns=\"http://schemas.microsoft.com/deepzoom/2008\">"
                        + "<Size Width=\"192\" Height=\"128\"/></Image>");
        var level = Files.createDirectories(root.resolve("slide_files/8"));
        for (var y = 0; y < 2; y++) {
            for (var x = 0; x < 3; x++) {
                var image = new BufferedImage(65, 65, BufferedImage.TYPE_INT_RGB);
                for (var py = 0; py < 65; py++) {
                    for (var px = 0; px < 65; px++) {
                        var dark = ((px + x * 11) % (17 + y * 2) < 8)
                                ^ ((py + y * 7) % (13 + x) < 6);
                        image.setRGB(
                                px,
                                py,
                                (dark
                                        ? new Color(82 + x * 17, 45 + y * 16, 120 + x * 8)
                                        : new Color(229, 171 - x * 7, 199 - y * 5))
                                        .getRGB());
                    }
                }
                ImageIO.write(image, "jpg", level.resolve(x + "_" + y + ".jpg").toFile());
            }
        }
        return new LocalPreview(root, 192, 128, 1_920, 1_280);
    }

    private LocalDataset dataset() {
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
                1_920,
                1_280,
                1,
                0,
                0,
                0,
                1_920,
                1_280,
                "fingerprint-one",
                "inventory",
                "config",
                "",
                "");
    }
}
