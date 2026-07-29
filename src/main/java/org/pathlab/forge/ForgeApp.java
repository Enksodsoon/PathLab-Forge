package org.pathlab.forge;

import java.awt.Desktop;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import org.pathlab.forge.server.ForgeServer;
import org.pathlab.forge.library.DatasetInspectionException;
import org.pathlab.forge.library.DatasetInspector;
import org.pathlab.forge.library.ForgePaths;
import org.pathlab.forge.library.PropertiesDatasetRepository;

public final class ForgeApp {
    private ForgeApp() {}

    public static void main(String[] args)
            throws IOException, InterruptedException, DatasetInspectionException {
        var arguments = Arrays.asList(args);
        var importIndex = arguments.indexOf("--import");
        if (importIndex >= 0) {
            if (importIndex + 1 >= arguments.size()) {
                throw new IllegalArgumentException("--import requires a local dataset path");
            }
            var paths = ForgePaths.defaults();
            var repository = new PropertiesDatasetRepository(paths.repositoryFile());
            var dataset = new DatasetInspector().inspect(
                    java.nio.file.Path.of(arguments.get(importIndex + 1)));
            if (repository.findBySourcePath(dataset.sourcePath()).isEmpty()) {
                repository.save(dataset);
                System.out.println("Imported dataset into the local Forge library.");
            } else {
                System.out.println("Dataset is already present in the local Forge library.");
            }
            return;
        }
        if (!Arrays.asList(args).contains("--serve")) {
            System.out.println("PathLab Forge core is available. Run with --serve to open the local shell.");
            return;
        }
        var stopped = new CountDownLatch(1);
        var server = ForgeServer.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            stopped.countDown();
        }, "pathlab-forge-shutdown"));
        System.out.println("PathLab Forge is available at " + server.baseUri());
        System.out.println("Open this one-time local URL: " + server.launchUri());
        if (!Arrays.asList(args).contains("--no-browser")
                && Desktop.isDesktopSupported()
                && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(server.launchUri());
        }
        stopped.await();
    }
}
