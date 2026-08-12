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
        String detail) {

    public FeaturePackDescriptor {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    }

    public FeaturePackDescriptor withState(String nextState, String nextDetail) {
        return new FeaturePackDescriptor(
                id, version, name, kind, nextState, downloadBytes, installedBytes,
                minimumMemoryBytes, minimumProcessors, pretrained, trainingOnly,
                license, downloadUri, sha256, signature, entrypoint, capabilities,
                nextDetail);
    }
}
