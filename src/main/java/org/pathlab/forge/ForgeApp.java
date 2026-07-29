package org.pathlab.forge;

import java.awt.Desktop;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import org.pathlab.forge.server.ForgeServer;

public final class ForgeApp {
    private ForgeApp() {}

    public static void main(String[] args) throws IOException, InterruptedException {
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
