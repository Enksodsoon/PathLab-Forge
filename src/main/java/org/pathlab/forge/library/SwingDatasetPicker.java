package org.pathlab.forge.library;

import java.awt.FileDialog;
import java.awt.Frame;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class SwingDatasetPicker implements DatasetPicker {
    @Override
    public List<Path> select() throws IOException {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            throw new IOException("Native file selection is unavailable in headless mode.");
        }
        var owner = new Frame();
        owner.setUndecorated(true);
        owner.setAlwaysOnTop(true);
        var dialog = new FileDialog(owner, "Add pathology datasets", FileDialog.LOAD);
        try {
            dialog.setAlwaysOnTop(true);
            dialog.setMultipleMode(true);
            dialog.setFilenameFilter((directory, name) -> {
                var lower = name.toLowerCase(java.util.Locale.ROOT);
                return lower.endsWith(".vsi")
                        || lower.endsWith(".ome.tif")
                        || lower.endsWith(".ome.tiff");
            });
            java.awt.EventQueue.invokeLater(() -> {
                dialog.toFront();
                dialog.requestFocus();
            });
            dialog.setVisible(true);
            var paths = new ArrayList<Path>();
            for (var file : dialog.getFiles()) {
                paths.add(file.toPath());
            }
            return List.copyOf(paths);
        } finally {
            dialog.dispose();
            owner.dispose();
        }
    }
}
