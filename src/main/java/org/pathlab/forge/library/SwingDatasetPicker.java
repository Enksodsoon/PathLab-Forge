package org.pathlab.forge.library;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.filechooser.FileFilter;

public final class SwingDatasetPicker implements DatasetPicker {
    @Override
    public List<Path> select() throws IOException {
        return select(false);
    }

    @Override
    public Path selectFolder() throws IOException {
        var selected = select(true);
        return selected.isEmpty() ? null : selected.get(0);
    }

    private List<Path> select(boolean folder) throws IOException {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            throw new IOException("Native file selection is unavailable in headless mode.");
        }
        if (SwingUtilities.isEventDispatchThread()) {
            return selectOnEventThread(folder);
        }
        var selected = new AtomicReference<List<Path>>(List.of());
        var failure = new AtomicReference<IOException>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    selected.set(selectOnEventThread(folder));
                } catch (IOException error) {
                    failure.set(error);
                }
            });
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("File selection was interrupted", error);
        } catch (InvocationTargetException error) {
            throw new IOException("Windows could not open the file chooser", error.getCause());
        }
        if (failure.get() != null) {
            throw failure.get();
        }
        return selected.get();
    }

    private static List<Path> selectOnEventThread(boolean folder) throws IOException {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (ReflectiveOperationException | javax.swing.UnsupportedLookAndFeelException ignored) {
            // The cross-platform Swing appearance remains usable.
        }
        var owner = new JFrame();
        owner.setUndecorated(true);
        owner.setAlwaysOnTop(true);
        owner.setSize(1, 1);
        owner.setLocationRelativeTo(null);
        owner.setVisible(true);
        try {
            var chooser = new JFileChooser();
            chooser.setDialogTitle(folder ? "Import PathLab project folder" : "Add pathology datasets");
            chooser.setMultiSelectionEnabled(!folder);
            chooser.setFileSelectionMode(folder ? JFileChooser.DIRECTORIES_ONLY : JFileChooser.FILES_ONLY);
            chooser.setAcceptAllFileFilterUsed(folder);
            if (!folder) chooser.setFileFilter(new FileFilter() {
                @Override
                public boolean accept(File file) {
                    return file.isDirectory() || supported(file.getName());
                }

                @Override
                public String getDescription() {
                    return "Pathology slides (*.svs, *.vsi, *.ome.tif, *.ome.tiff)";
                }
            });
            var downloads = Path.of(System.getProperty("user.home"), "Downloads");
            if (Files.isDirectory(downloads)) {
                chooser.setCurrentDirectory(downloads.toFile());
            }
            owner.toFront();
            owner.requestFocus();
            if (chooser.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) {
                return List.of();
            }
            var files = chooser.getSelectedFiles();
            if (files.length == 0 && chooser.getSelectedFile() != null) {
                files = new File[] {chooser.getSelectedFile()};
            }
            return java.util.Arrays.stream(files)
                    .map(File::toPath)
                    .map(Path::toAbsolutePath)
                    .map(Path::normalize)
                    .toList();
        } finally {
            owner.dispose();
        }
    }

    private static boolean supported(String name) {
        var lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".vsi")
                || lower.endsWith(".svs")
                || lower.endsWith(".ome.tif")
                || lower.endsWith(".ome.tiff");
    }
}
