package org.pathlab.forge.library;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class DatasetPreparationService {
    private final DatasetRepository repository;
    private final Path managedRoot;

    public DatasetPreparationService(DatasetRepository repository, Path managedRoot) {
        this.repository = repository;
        this.managedRoot = managedRoot.toAbsolutePath().normalize();
    }

    public LocalDataset prepare(String id) throws IOException {
        var dataset = repository.find(id).orElseThrow(() ->
                new IllegalArgumentException("Dataset was not found"));
        if (dataset.format() != DatasetFormat.OME_TIFF) {
            throw new IllegalStateException("VSI conversion reader is not installed");
        }
        var outputDirectory = managedRoot.resolve(dataset.id());
        Files.createDirectories(outputDirectory);
        var output = outputDirectory.resolve("source.ome.tif");
        var partial = output.resolveSibling(output.getFileName() + ".partial");
        try {
            Files.copy(
                    Path.of(dataset.sourcePath()),
                    partial,
                    StandardCopyOption.REPLACE_EXISTING);
            var digest = sha256(partial);
            try {
                Files.move(
                        partial,
                        output,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(partial, output, StandardCopyOption.REPLACE_EXISTING);
            }
            var prepared = dataset.withPreparation(
                    DatasetStatus.LOCAL_COPY_READY,
                    "Managed OME-TIFF copy ready; source preserved",
                    output.toString(),
                    digest);
            repository.save(prepared);
            return prepared;
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    private static String sha256(Path path) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                var buffer = new byte[1024 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
