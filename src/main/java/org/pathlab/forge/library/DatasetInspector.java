package org.pathlab.forge.library;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

public final class DatasetInspector {
    private static final long MAX_COMPANION_ENTRIES = 10_000;

    public LocalDataset inspect(Path input) throws IOException, DatasetInspectionException {
        var source = input.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) {
            throw new DatasetInspectionException("SOURCE_NOT_FOUND", "Selected source is not a file");
        }
        var lowerName = source.getFileName().toString().toLowerCase(Locale.ROOT);
        if (lowerName.endsWith(".ome.tif") || lowerName.endsWith(".ome.tiff")) {
            verifyTiffSignature(source);
            return dataset(
                    source,
                    DatasetFormat.OME_TIFF,
                    DatasetStatus.READY,
                    "OME-TIFF signature verified; ready for managed local copy");
        }
        if (lowerName.endsWith(".vsi")) {
            if (!hasEtsCompanion(source.getParent())) {
                return dataset(
                        source,
                        DatasetFormat.VSI,
                        DatasetStatus.NEEDS_COMPANIONS,
                        "No .ets companion found within the bounded dataset search");
            }
            return dataset(
                    source,
                    DatasetFormat.VSI,
                    DatasetStatus.READER_REQUIRED,
                    "Complete VSI/ETS set found; Bio-Formats reader license/runtime required");
        }
        throw new DatasetInspectionException(
                "UNSUPPORTED_FORMAT", "Only OME-TIFF and VSI datasets are supported");
    }

    private static LocalDataset dataset(
            Path source, DatasetFormat format, DatasetStatus status, String detail)
            throws IOException {
        return new LocalDataset(
                UUID.randomUUID().toString(),
                source.getFileName().toString(),
                source.toString(),
                Files.size(source),
                format,
                status,
                detail,
                "",
                "");
    }

    private static void verifyTiffSignature(Path source)
            throws IOException, DatasetInspectionException {
        var signature = new byte[4];
        try (InputStream input = Files.newInputStream(source)) {
            if (input.read(signature) != signature.length) {
                throw new DatasetInspectionException(
                        "INVALID_TIFF_SIGNATURE", "OME-TIFF file is too short");
            }
        }
        var littleClassic = signature[0] == 'I'
                && signature[1] == 'I'
                && signature[2] == 42
                && signature[3] == 0;
        var littleBig = signature[0] == 'I'
                && signature[1] == 'I'
                && signature[2] == 43
                && signature[3] == 0;
        var bigClassic = signature[0] == 'M'
                && signature[1] == 'M'
                && signature[2] == 0
                && signature[3] == 42;
        var bigBig = signature[0] == 'M'
                && signature[1] == 'M'
                && signature[2] == 0
                && signature[3] == 43;
        if (!littleClassic && !littleBig && !bigClassic && !bigBig) {
            throw new DatasetInspectionException(
                    "INVALID_TIFF_SIGNATURE", "Selected OME-TIFF has an invalid TIFF signature");
        }
    }

    private static boolean hasEtsCompanion(Path root) throws IOException {
        try (Stream<Path> paths =
                Files.find(root, 3, (path, attributes) -> attributes.isRegularFile())) {
            return paths.limit(MAX_COMPANION_ENTRIES)
                    .anyMatch(path -> path.getFileName()
                            .toString()
                            .toLowerCase(Locale.ROOT)
                            .endsWith(".ets"));
        }
    }
}
