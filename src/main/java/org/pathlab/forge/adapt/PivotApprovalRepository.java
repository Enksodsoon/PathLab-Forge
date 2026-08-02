package org.pathlab.forge.adapt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import org.pathlab.forge.pivot.PivotManifest;

public final class PivotApprovalRepository {
    private final Path root;

    public PivotApprovalRepository(Path managedRoot) {
        root = managedRoot.toAbsolutePath().normalize()
                .resolve("research").resolve("adapt-v1").resolve("pivot-approvals");
    }

    public synchronized PivotApproval approve(PivotManifest manifest, String approvedBy, long approvedAt) throws IOException {
        if (approvedBy == null || approvedBy.isBlank() || approvedAt < 1) {
            throw new IllegalArgumentException("PIVOT approval identity and time are required");
        }
        var approval = new PivotApproval(manifest.datasetId(), manifest.id(), manifest.sourceFingerprint(),
                manifest.inputRevision(), approvedBy.trim(), approvedAt);
        var values = new Properties();
        values.setProperty("datasetId", approval.datasetId()); values.setProperty("manifestId", approval.manifestId());
        values.setProperty("sourceFingerprint", approval.sourceFingerprint()); values.setProperty("inputRevision", approval.inputRevision());
        values.setProperty("approvedBy", approval.approvedBy()); values.setProperty("approvedAt", Long.toString(approval.approvedAt()));
        var target = target(manifest.datasetId()); Files.createDirectories(target.getParent());
        var partial = target.resolveSibling(target.getFileName() + ".partial");
        try (var writer = Files.newBufferedWriter(partial, StandardCharsets.UTF_8)) { values.store(writer, null); }
        try {
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return approval;
    }

    public PivotApproval requireApproved(PivotManifest manifest) throws IOException {
        var target = target(manifest.datasetId());
        if (!Files.isRegularFile(target)) throw new IllegalArgumentException("PIVOT manifest lacks durable faculty approval");
        var values = new Properties();
        try (var reader = Files.newBufferedReader(target, StandardCharsets.UTF_8)) { values.load(reader); }
        var approval = new PivotApproval(values.getProperty("datasetId"), values.getProperty("manifestId"),
                values.getProperty("sourceFingerprint"), values.getProperty("inputRevision"),
                values.getProperty("approvedBy"), Long.parseLong(values.getProperty("approvedAt", "0")));
        if (!approval.datasetId().equals(manifest.datasetId()) || !approval.manifestId().equals(manifest.id())
                || !approval.sourceFingerprint().equals(manifest.sourceFingerprint())
                || !approval.inputRevision().equals(manifest.inputRevision())) {
            throw new IllegalArgumentException("PIVOT approval does not match the current manifest revision");
        }
        return approval;
    }

    private Path target(String datasetId) {
        if (datasetId == null || !datasetId.matches("[A-Za-z0-9._-]+")) throw new IllegalArgumentException("Dataset identifier is invalid");
        return root.resolve(datasetId + ".properties").toAbsolutePath().normalize();
    }
}
