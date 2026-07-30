package org.pathlab.forge.library;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

public final class DatasetInspector {
    public LocalDataset inspect(Path input) throws IOException, DatasetInspectionException {
        var source = input.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) {
            throw new DatasetInspectionException("SOURCE_NOT_FOUND", "Selected source is not a file");
        }
        var lowerName = source.getFileName().toString().toLowerCase(Locale.ROOT);
        if (lowerName.endsWith(".ome.tif") || lowerName.endsWith(".ome.tiff")) {
            verifyTiffSignature(source);
            var inventory = DatasetSourceInventory.singleFile(source);
            return dataset(
                    source,
                    DatasetFormat.OME_TIFF,
                    DatasetStatus.READY,
                    "OME-TIFF signature verified; ready for managed local copy",
                    inventory);
        }
        if (lowerName.endsWith(".vsi")) {
            var inventory = DatasetSourceInventory.forVsi(source);
            if (inventory.fingerprint().isEmpty()) {
                return dataset(
                        source,
                        DatasetFormat.VSI,
                        DatasetStatus.NEEDS_COMPANIONS,
                        "No matching CellSens .ets companion set was found",
                        inventory);
            }
            return dataset(
                    source,
                    DatasetFormat.VSI,
                    DatasetStatus.READER_REQUIRED,
                    "Complete VSI/ETS set found; Bio-Formats reader license/runtime required",
                    inventory);
        }
        throw new DatasetInspectionException(
                "UNSUPPORTED_FORMAT", "Only OME-TIFF and VSI datasets are supported");
    }

    private static LocalDataset dataset(
            Path source,
            DatasetFormat format,
            DatasetStatus status,
            String detail,
            DatasetSourceInventory inventory)
            throws IOException {
        return new LocalDataset(
                stableDatasetId(source),
                source.getFileName().toString(),
                source.toString(),
                inventory.totalBytes(),
                format,
                status,
                detail,
                "",
                "",
                -1,
                0,
                0,
                1.0,
                0,
                0,
                0,
                0,
                0,
                inventory.fingerprint(),
                inventory.serialized(),
                "",
                "",
                "");
    }

    private static String stableDatasetId(Path source) {
        var identity = source.toString();
        if (java.io.File.separatorChar == '\\') {
            identity = identity.toLowerCase(Locale.ROOT);
        }
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
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

}
