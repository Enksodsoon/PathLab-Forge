package org.pathlab.forge.reader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.pathlab.forge.library.DatasetFormat;
import org.pathlab.forge.library.DatasetRepository;
import org.pathlab.forge.library.DatasetStatus;
import org.pathlab.forge.library.DatasetInspectionException;
import org.pathlab.forge.library.LocalDataset;
import org.pathlab.forge.library.SourceSnapshot;

public final class UniversalDatasetImporter {
    private final DatasetRepository repository;
    private final DatasetProbe probe;

    public UniversalDatasetImporter(DatasetRepository repository, DatasetProbe probe) {
        this.repository = repository;
        this.probe = probe;
    }

    public Result importPaths(List<Path> paths) throws IOException {
        var datasets = new ArrayList<LocalDataset>();
        var diagnostics = new ArrayList<ImportDiagnostic>();
        for (var input : paths) {
            var source = input.toAbsolutePath().normalize();
            if (!Files.isRegularFile(source)) {
                diagnostics.add(new ImportDiagnostic(
                        ImportDiagnostic.Code.UNSUPPORTED,
                        "Selected source is not a regular file",
                        List.of(source)));
                continue;
            }
            var existing = repository.findBySourcePath(source.toString());
            if (existing.isPresent()) {
                datasets.add(existing.orElseThrow());
                continue;
            }
            try {
                var detected = probe.probe(source);
                var snapshot = legacyVsiProbe(source, detected)
                        ? SourceSnapshot.forVsi(source)
                        : SourceSnapshot.forUsedFiles(source, detected.usedFiles());
                var format = DatasetFormat.valueOf(formatKey(source, detected));
                var dataset = new LocalDataset(
                        stableId(source), source.getFileName().toString(), source.toString(),
                        snapshot.totalBytes(), format, DatasetStatus.VERIFYING_SOURCE,
                        "Reader detected " + detected.formatName() + "; verifying source content",
                        "", "")
                        .withSourceIdentity(
                                DatasetStatus.VERIFYING_SOURCE,
                                "Reader detected " + detected.formatName()
                                        + "; verifying source content",
                                "",
                                snapshot.serialized())
                        .withReaderMetadata(
                                detected.descriptor().engine(), detected.descriptor().readerId(),
                                detected.formatName(), detected.runtimeFingerprint(), "");
                repository.save(dataset);
                datasets.add(dataset);
            } catch (ImportProbeException error) {
                var diagnostic = error.diagnostic();
                diagnostics.add(diagnostic);
                if (diagnostic.code().repairable()) {
                    var status = diagnostic.code() == ImportDiagnostic.Code.MISSING_COMPANION
                            ? DatasetStatus.NEEDS_COMPANIONS : DatasetStatus.READER_REQUIRED;
                    var dataset = new LocalDataset(
                            stableId(source), source.getFileName().toString(), source.toString(),
                            Files.size(source), DatasetFormat.valueOf("UNKNOWN"), status,
                            diagnostic.detail(), "", "");
                    repository.save(dataset);
                    datasets.add(dataset);
                }
            } catch (IOException | DatasetInspectionException error) {
                diagnostics.add(new ImportDiagnostic(
                        ImportDiagnostic.Code.CORRUPT,
                        error.getMessage() == null ? "Reader could not open source" : error.getMessage(),
                        List.of(source)));
            } catch (RuntimeException error) {
                diagnostics.add(new ImportDiagnostic(
                        ImportDiagnostic.Code.CODEC_UNAVAILABLE,
                        error.getMessage() == null ? "Image reader runtime is unavailable"
                                : error.getMessage(),
                        List.of(source)));
            }
        }
        return new Result(List.copyOf(datasets), List.copyOf(diagnostics));
    }

    private static boolean legacyVsiProbe(Path source, DatasetProbe.Result detected) {
        return detected.descriptor().engine().equals("UNKNOWN")
                && detected.usedFiles().size() == 1
                && source.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".vsi");
    }

    private static String formatKey(Path source, DatasetProbe.Result detected) {
        if (detected.descriptor().engine().equals("UNKNOWN")) {
            var name = source.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.endsWith(".vsi")) return "VSI";
            if (name.endsWith(".svs")) return "SVS";
            if (name.endsWith(".ome.tif") || name.endsWith(".ome.tiff")) return "OME_TIFF";
        }
        var readerId = detected.descriptor().readerId();
        var value = readerId.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("(^_+|_+$)", "");
        return value.isEmpty() ? "UNKNOWN" : value.substring(0, Math.min(96, value.length()));
    }

    private static String stableId(Path source) {
        var identity = source.toString();
        if (java.io.File.separatorChar == '\\') identity = identity.toLowerCase(Locale.ROOT);
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
    }

    public record Result(List<LocalDataset> datasets, List<ImportDiagnostic> diagnostics) {}
}
