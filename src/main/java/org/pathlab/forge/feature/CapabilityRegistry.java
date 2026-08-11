package org.pathlab.forge.feature;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class CapabilityRegistry {
    private final FeaturePackManager packs;

    public CapabilityRegistry(FeaturePackManager packs) {
        this.packs = packs;
    }

    public List<Capability> list() {
        var result = new ArrayList<Capability>();
        result.add(new Capability("core.wsi", "core", "AVAILABLE", "WSI inspection, preview, conversion and upload"));
        result.add(new Capability("core.annotations", "core", "AVAILABLE", "Manual annotation drawing"));
        result.add(new Capability("core.measurements", "core", "AVAILABLE", "Bounded geometry measurements"));
        for (var pack : packs.list()) {
            for (var capability : pack.capabilities()) {
                result.add(new Capability(capability, pack.id(), pack.state(), pack.detail()));
            }
        }
        result.sort(Comparator.comparing(Capability::id));
        return List.copyOf(result);
    }

    public record Capability(String id, String provider, String state, String detail) {}
}
