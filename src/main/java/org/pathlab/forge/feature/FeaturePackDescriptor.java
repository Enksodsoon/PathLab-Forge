package org.pathlab.forge.feature;

import java.net.URI;
import java.util.List;

public record FeaturePackDescriptor(
        String id,
        String version,
        String name,
        String kind,
        String state,
        long downloadBytes,
        long installedBytes,
        long minimumMemoryBytes,
        int minimumProcessors,
        boolean pretrained,
        boolean trainingOnly,
        String license,
        URI downloadUri,
        String sha256,
        String signature,
        String entrypoint,
        List<String> capabilities,
        String detail,
        List<String> platforms,
        String minimumCoreVersion,
        String licenseReviewStatus,
        String activeVersion,
        List<String> installedVersions) {

    public FeaturePackDescriptor {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        platforms = platforms == null ? List.of() : List.copyOf(platforms);
        installedVersions = installedVersions == null ? List.of() : List.copyOf(installedVersions);
        activeVersion = activeVersion == null ? "" : activeVersion;
    }

    public FeaturePackDescriptor(String id, String version, String name, String kind, String state,
            long downloadBytes, long installedBytes, long minimumMemoryBytes, int minimumProcessors,
            boolean pretrained, boolean trainingOnly, String license, URI downloadUri, String sha256,
            String signature, String entrypoint, List<String> capabilities, String detail) {
        this(id, version, name, kind, state, downloadBytes, installedBytes, minimumMemoryBytes,
                minimumProcessors, pretrained, trainingOnly, license, downloadUri, sha256, signature,
                entrypoint, capabilities, detail, List.of(), "", "PENDING_REVIEW", "", List.of());
    }

    public FeaturePackDescriptor withInstallation(String active, List<String> versions) {
        return new FeaturePackDescriptor(id, version, name, kind, state, downloadBytes, installedBytes,
                minimumMemoryBytes, minimumProcessors, pretrained, trainingOnly, license, downloadUri,
                sha256, signature, entrypoint, capabilities, detail, platforms, minimumCoreVersion,
                licenseReviewStatus, active, versions);
    }

    public FeaturePackDescriptor withState(String nextState, String nextDetail) {
        return new FeaturePackDescriptor(
                id, version, name, kind, nextState, downloadBytes, installedBytes,
                minimumMemoryBytes, minimumProcessors, pretrained, trainingOnly,
                license, downloadUri, sha256, signature, entrypoint, capabilities,
                nextDetail, platforms, minimumCoreVersion, licenseReviewStatus, activeVersion, installedVersions);
    }
}
