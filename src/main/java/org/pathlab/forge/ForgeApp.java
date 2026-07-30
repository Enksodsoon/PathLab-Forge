package org.pathlab.forge;

import java.awt.Desktop;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import org.pathlab.forge.server.ForgeServer;
import org.pathlab.forge.library.DatasetInspectionException;
import org.pathlab.forge.library.DatasetInspector;
import org.pathlab.forge.library.ForgePaths;
import org.pathlab.forge.library.SqliteDatasetRepository;
import org.pathlab.forge.runtime.DataRootLock;
import org.pathlab.forge.runtime.ForgeCommandLine;
import org.pathlab.forge.benchmark.ForgeBenchmark;

public final class ForgeApp {
    private ForgeApp() {}

    public static void main(String[] args)
            throws IOException, InterruptedException, DatasetInspectionException {
        var command = ForgeCommandLine.parse(args);
        var paths = command.dataRoot() == null
                ? ForgePaths.defaults()
                : ForgePaths.at(command.dataRoot());
        try (var dataRootLock = acquireOrOpenExisting(paths, command)) {
            if (dataRootLock == null) {
                return;
            }
            try (var repository = new SqliteDatasetRepository(
                    paths.repositoryFile(),
                    paths.dataRoot().resolve("library.properties"))) {
                if (command.benchmarkSource() != null) {
                    var reportPath = command.performanceReport() == null
                            ? paths.dataRoot().resolve("performance-report.json")
                            : command.performanceReport();
                    var report =
                            ForgeBenchmark.run(command.benchmarkSource(), paths, repository, reportPath);
                    System.out.print(report.toJson());
                    if (!report.hardGatesPassed()) {
                        throw new IllegalStateException(
                                "Benchmark hard gates failed: " + report.failure());
                    }
                    return;
                }
                if (command.importSource() != null) {
                    var dataset = new DatasetInspector().inspect(command.importSource());
                    if (repository.findBySourcePath(dataset.sourcePath()).isEmpty()) {
                        repository.save(dataset);
                        System.out.println("Imported dataset into the local Forge library.");
                    } else {
                        System.out.println("Dataset is already present in the local Forge library.");
                    }
                    return;
                }
                if (!command.serve()) {
                    System.out.println(
                            "PathLab Forge core is available. Run with --serve to open the local shell.");
                    return;
                }
                var stopped = new CountDownLatch(1);
                var server = ForgeServer.start(paths, repository);
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    server.close();
                    stopped.countDown();
                }, "pathlab-forge-shutdown"));
                System.out.println("PathLab Forge permanent local link: " + server.appUri());
                System.out.println("Authorize a new browser once with: " + server.launchUri());
                if (!command.noBrowser()
                        && Desktop.isDesktopSupported()
                        && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(server.appUri());
                }
                stopped.await();
            }
        }
    }

    private static DataRootLock acquireOrOpenExisting(
            ForgePaths paths, ForgeCommandLine command) throws IOException {
        try {
            return DataRootLock.acquire(paths.dataRoot());
        } catch (IOException error) {
            if (!command.serve()) {
                throw error;
            }
            var uri = java.net.URI.create("http://127.0.0.1:51274/app");
            System.out.println("PathLab Forge is already running: " + uri);
            if (!command.noBrowser()
                    && Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(uri);
            }
            return null;
        }
    }
}
