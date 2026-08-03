package org.pathlab.forge.ai;

import java.util.List;

/** Fail-closed catalogue. Runtime adapters are activated only after pinned local review. */
public final class MorphologyModelRouter {
    private MorphologyModelRouter() {}
    public static List<Model> catalogue() {
        return List.of(
                new Model("kaiko", List.of("he"), "approved-local-adapter-required", false, false),
                new Model("gigapath-flash", List.of("he"), "gated-terms-and-bakeoff", false, false),
                new Model("hibou-b", List.of("he", "ihc_dab", "pas", "masson_trichrome", "reticulin"), "pinned-adapter-and-bakeoff", false, false),
                new Model("generic-dino", List.of("he", "ihc_dab", "pas", "masson_trichrome", "reticulin"), "paired-baseline", false, false),
                new Model("virchow2", List.of(), "catalogue-only-8gb", false, false),
                new Model("uni2-h", List.of(), "catalogue-only-8gb", false, false),
                new Model("conch", List.of(), "catalogue-only-gated-noncommercial", false, false),
                new Model("prov-gigapath-full", List.of(), "catalogue-only-8gb", false, false));
    }
    public record Model(String id, List<String> supportedStains, String activation, boolean enabled, boolean trustRemoteCode) {}
}
