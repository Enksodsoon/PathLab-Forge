package org.pathlab.forge.evidence;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class DashboardLauncherContractTest {
    @Test
    void launcherPreservesOneTimeFragmentWithAnExplicitBrowserProcess() throws Exception {
        var script = Files.readString(Path.of("scripts/open-evidence-dashboard.ps1"));
        assertTrue(script.contains("$url = \"$origin/dashboard/#$($session.code)\""));
        assertTrue(script.contains("Start-Process -FilePath $browser -ArgumentList"));
        assertTrue(script.contains("'--new-tab'"));
        assertFalse(script.contains("Start-Process \"$origin/dashboard/#$($session.code)\""));
    }

    @Test
    void failedUpgradeRestoresMarkerFromActualPreviousRuntimeConfig() throws Exception {
        var script = Files.readString(Path.of("scripts/evidence-mentor-service.ps1"));
        assertTrue(script.contains("$previousRuntimeVersion = $previousVersion"));
        assertTrue(script.contains("$previousConfig -match"));
        assertTrue(script.contains("Install-FirewallRules (Join-Path $runtimeRoot $previousRuntimeVersion)"));
        assertTrue(script.contains("Write-AtomicText $activeVersionPath $previousRuntimeVersion"));
    }

    @Test
    void installedStateRepairRequiresAgreementBeforeCorrectingTheMarker() throws Exception {
        var script = Files.readString(Path.of("scripts/repair-evidence-mentor-installed-state.ps1"));
        assertTrue(script.contains("$endpoint.serviceVersion -ne $runtimeVersion"));
        assertTrue(script.contains("$status.serviceVersion -ne $runtimeVersion"));
        assertTrue(script.contains("open-evidence-dashboard.ps1"));
        assertTrue(script.contains("install-evidence-mentor-post-reboot-continuation.ps1"));
    }
}
