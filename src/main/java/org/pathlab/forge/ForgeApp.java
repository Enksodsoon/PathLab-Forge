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
import org.pathlab.forge.runtime.DataUpgradeRecovery;
import org.pathlab.forge.runtime.DesktopStartup;
import org.pathlab.forge.runtime.ForgeCommandLine;
import org.pathlab.forge.runtime.ReaderRuntimeSelfTest;
import org.pathlab.forge.benchmark.ForgeBenchmark;

public final class ForgeApp {
    private ForgeApp() {}

    public static void main(String[] args)
            throws IOException, InterruptedException, DatasetInspectionException {
        var command = ForgeCommandLine.parse(args);
        try {
            run(command);
        } catch (IOException | RuntimeException error) {
            if (!command.desktop()) throw error;
            DesktopStartup.failed(System.out, error instanceof DataRootLock.AlreadyOwnedException
                    ? "DATA_LOCKED" : error instanceof java.nio.file.AccessDeniedException
                    ? "DATA_DENIED" : error instanceof DataUpgradeRecovery.Failure upgrade
                    ? upgrade.startupCode() : "SERVICE_UNAVAILABLE");
        }
    }

    private static void run(ForgeCommandLine command)
            throws IOException, InterruptedException, DatasetInspectionException {
        if (!command.readerSelfTest() && (command.dataRoot() == null || command.desktop())) ForgePaths.migrateLegacyMacData();
        var paths = command.dataRoot() == null
                ? ForgePaths.defaults()
                : ForgePaths.at(command.dataRoot());
        if (command.port() != null) {
            System.setProperty("pathlab.forge.port", Integer.toString(command.port()));
        }
        if (command.desktop()) System.setProperty("pathlab.forge.desktop", "true");
        try (var dataRootLock = acquireOrOpenExisting(paths, command)) {
            if (dataRootLock == null) {
                return;
            }
            if (command.readerSelfTest()) {
                System.out.println(ReaderRuntimeSelfTest.run(paths, command.selfTestSources()));
                return;
            }
            var installedVersion = ForgeApp.class.getPackage().getImplementationVersion();
            DataUpgradeRecovery.prepare(paths, dataRootLock,
                    installedVersion == null && Boolean.getBoolean("pathlab.forge.runtime.requireProduction")
                            ? "" : installedVersion);
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
                var closing = new java.util.concurrent.atomic.AtomicBoolean();
                Runnable stop = () -> {
                    if (closing.compareAndSet(false, true)) {
                        try {
                            server.close();
                        } finally {
                            stopped.countDown();
                        }
                    }
                };
                Runtime.getRuntime().addShutdownHook(new Thread(stop, "pathlab-forge-shutdown"));
                if (command.desktop()) {
                    DesktopStartup.ready(System.out, server.appUri(), server.launchUri(), server.desktopSecret());
                    DesktopStartup.watchOwner(System.in, stop);
                } else {
                    System.out.println("PathLab Forge permanent local link: " + server.appUri());
                    System.out.println("Authorize a new browser once with: " + server.launchUri());
                }
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
        } catch (DataRootLock.AlreadyOwnedException error) {
            if (!command.serve() || command.desktop() || command.port() != null) {
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
