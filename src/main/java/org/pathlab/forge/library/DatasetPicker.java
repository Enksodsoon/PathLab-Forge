package org.pathlab.forge.library;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

@FunctionalInterface
public interface DatasetPicker {
    List<Path> select() throws IOException;
}
