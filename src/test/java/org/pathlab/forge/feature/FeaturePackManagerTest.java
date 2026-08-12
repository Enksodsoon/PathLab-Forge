package org.pathlab.forge.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.file.Files;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FeaturePackManagerTest {
    @TempDir Path temp;

    @Test
    void staysOfflineAndReportsOnlyHonestUnavailablePackStates() {
        var manager = new FeaturePackManager(temp);

        var packs = manager.list();

        assertEquals(4, packs.size());
        assertEquals("UNAVAILABLE", packs.stream()
                .filter(pack -> pack.id().equals("pretrained-ai"))
                .findFirst().orElseThrow().state());
        assertThrows(IOException.class, manager::refresh);
    }

    @Test
    void refusesUnverifiedOrUnknownInstallations() {
        var manager = new FeaturePackManager(temp);

        assertThrows(IOException.class, () -> manager.install("pathology-tools"));
        assertThrows(IOException.class, () -> manager.uninstall("../outside"));
    }

    @Test
    void reloadsAndDisablesAnInstalledPackWithoutDeletingIt() throws Exception {
        var installed = temp.resolve("feature-packs/pathology-tools/1.0.0");
        Files.createDirectories(installed);
        new ObjectMapper().writeValue(installed.resolve("installed.json").toFile(), new FeaturePackDescriptor(
                "pathology-tools", "1.0.0", "Pathology Tools", "PATHOLOGY", "AVAILABLE",
                12, 24, 0, 1, false, false, "Apache-2.0", URI.create("https://packs.pathlab.test/tools.zip"),
                "0".repeat(64), "signature", "self-test.jar", List.of("pathology.he"), "Verified"));

        var manager = new FeaturePackManager(temp);
        assertEquals("INSTALLED", manager.list().stream()
                .filter(pack -> pack.id().equals("pathology-tools")).findFirst().orElseThrow().state());
        assertTrue(manager.isInstalled("pathology-tools"));

        manager.disable("pathology-tools");

        assertEquals("DISABLED", manager.list().stream()
                .filter(pack -> pack.id().equals("pathology-tools")).findFirst().orElseThrow().state());
        assertFalse(manager.isInstalled("pathology-tools"));
        assertTrue(Files.isRegularFile(installed.resolve("installed.json")));
    }
}
