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
        var pending = inspectFast(input);
        if (pending.status() == DatasetStatus.NEEDS_COMPANIONS) {
            return pending;
        }
        var snapshot = snapshot(Path.of(pending.sourcePath()), pending.format());
        var digest = SourceDigest.compute(snapshot);
        return pending.withSourceIdentity(
                readyStatus(pending.format(), snapshot),
                readyDetail(pending.format(), snapshot),
                digest.fingerprint(),
                digest.serializedInventory());
    }

    public LocalDataset inspectFast(Path input) throws IOException, DatasetInspectionException {
        var source = input.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) {
            throw new DatasetInspectionException("SOURCE_NOT_FOUND", "Selected source is not a file");
        }
        var lowerName = source.getFileName().toString().toLowerCase(Locale.ROOT);
        if (lowerName.endsWith(".ome.tif") || lowerName.endsWith(".ome.tiff")) {
            verifyTiffSignature(source);
            var snapshot = SourceSnapshot.singleFile(source);
            return dataset(
                    source,
                    DatasetFormat.OME_TIFF,
                    DatasetStatus.VERIFYING_SOURCE,
                    "Source snapshot captured; verifying content in background",
                    snapshot);
        }
        if (lowerName.endsWith(".svs")) {
            verifyTiffSignature(source);
            var snapshot = SourceSnapshot.singleFile(source);
            return dataset(
                    source,
                    DatasetFormat.SVS,
                    DatasetStatus.VERIFYING_SOURCE,
                    "SVS snapshot captured; verifying content in background",
                    snapshot);
        }
        if (lowerName.endsWith(".vsi")) {
            var snapshot = SourceSnapshot.forVsi(source);
            if (!snapshot.hasEtsCompanion()) {
                return dataset(
                        source,
                        DatasetFormat.VSI,
                        DatasetStatus.NEEDS_COMPANIONS,
                        "No matching CellSens .ets companion set was found",
                        snapshot)
                        .withSourceIdentity(
                                DatasetStatus.NEEDS_COMPANIONS,
                                "No matching CellSens .ets companion set was found",
                                "",
                                "");
            }
            return dataset(
                    source,
                    DatasetFormat.VSI,
                    DatasetStatus.VERIFYING_SOURCE,
                    "VSI/ETS snapshot captured; verifying content in background",
                    snapshot);
        }
        throw new DatasetInspectionException(
                "UNSUPPORTED_FORMAT", "Only SVS, OME-TIFF and VSI datasets are supported");
    }

    private static LocalDataset dataset(
            Path source,
            DatasetFormat format,
            DatasetStatus status,
            String detail,
            SourceSnapshot snapshot)
            throws IOException {
        return new LocalDataset(
                stableDatasetId(source),
                source.getFileName().toString(),
                source.toString(),
                snapshot.totalBytes(),
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
                "",
                snapshot.serialized(),
                "",
                "",
                "");
    }

    static SourceSnapshot snapshot(Path source, DatasetFormat format)
            throws IOException, DatasetInspectionException {
        return format == DatasetFormat.VSI
                ? SourceSnapshot.forVsi(source)
                : SourceSnapshot.singleFile(source);
    }

    static DatasetStatus readyStatus(DatasetFormat format, SourceSnapshot snapshot) {
        if (format == DatasetFormat.VSI && !snapshot.hasEtsCompanion()) {
            return DatasetStatus.NEEDS_COMPANIONS;
        }
        return format == DatasetFormat.VSI
                ? DatasetStatus.READER_REQUIRED
                : DatasetStatus.READY;
    }

    static String readyDetail(DatasetFormat format, SourceSnapshot snapshot) {
        if (format == DatasetFormat.VSI && !snapshot.hasEtsCompanion()) {
            return "No matching CellSens .ets companion set was found";
        }
        if (format == DatasetFormat.VSI) {
            return "Complete VSI/ETS set verified; ready for Bio-Formats inspection";
        }
        return format == DatasetFormat.SVS
                ? "SVS TIFF signature verified and content digested; ready for native inspection"
                : "OME-TIFF signature verified and content digested; ready for managed local copy";
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
                        "INVALID_TIFF_SIGNATURE", "TIFF-based slide is too short");
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
                    "INVALID_TIFF_SIGNATURE", "Selected slide has an invalid TIFF signature");
        }
    }

}
