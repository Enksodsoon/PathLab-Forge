package org.pathlab.forge.evidence;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class AcquisitionScriptContractTest {
    @Test
    void zenodoAcquisitionNeverUsesUnsafeHttpResume() throws Exception {
        var script = Files.readString(Path.of("scripts/acquire-nct-crc-he.ps1"));
        assertFalse(script.contains("--continue-at"));
        assertTrue(script.contains("--remove-on-error"));
        assertTrue(script.contains("$process.WaitForExit()"));
        assertTrue(script.contains("$attempt -le 3"));
        assertTrue(script.contains("Move-ToQuarantine $partial"));
        assertTrue(script.contains("Downloaded size mismatch"));
        assertTrue(script.contains("Checksum validation failed"));
    }

    @Test
    void nctCrcCohortIsBoundedChecksumPinnedAndFailClosed() throws Exception {
        var script = Files.readString(Path.of("scripts/build-nct-crc-dinov2-cohort.ps1"));
        assertTrue(script.contains("[int] $SamplesPerClass = 20"));
        assertTrue(script.contains("$derivedQuotaBytes = 25GB"));
        assertTrue(script.contains("Get-StreamSha256"));
        assertTrue(script.contains("PATIENT_LEVEL_PATCH_MAPPING_UNAVAILABLE"));
        assertTrue(script.contains("qualificationStatus = 'not_evaluable'"));
        assertFalse(script.contains("Expand-Archive"));
    }

    @Test
    void serviceScriptsUseWindowsPowerShellCompatibleUtf8Writes() throws Exception {
        for (var path : new String[] {
                "scripts/acquire-dinov2-small.ps1",
                "scripts/build-bracs-dinov2-tile-cache.ps1",
                "scripts/prepare-all-rounder-campaign.ps1",
                "scripts/submit-evidence-job.ps1"}) {
            var script = Files.readString(Path.of(path));
            assertFalse(script.contains("utf8NoBOM"), path);
            assertTrue(script.contains("UTF8Encoding"), path);
        }
    }

    @Test
    void dinov2CohortWorkerIsChecksumBoundedRepeatableAndEmbeddingFree() throws Exception {
        var worker = Files.readString(Path.of(
                "src/main/resources/model-workers/dinov2-worker.py"));
        var stage = Files.readString(Path.of("scripts/stage-nct-crc-dinov2-retrieval.ps1"));

        assertTrue(worker.contains("pathlab.he-retrieval-metrics/1"));
        assertTrue(worker.contains("exactRankingRepeatability"));
        assertTrue(worker.contains("embeddingsExported\": False"));
        assertTrue(worker.contains("checkpoint(output_path"));
        assertTrue(worker.contains("model_features(model, processor, samples, output_path, pack_hash, cohort_hash, 1)"));
        assertFalse(worker.contains("result[\"embeddings\"]"));
        assertTrue(stage.contains("@($cohort.samples).Count -ne 360"));
        assertTrue(stage.contains("marker = \"cohort-$cohortSha\""));
        assertTrue(stage.contains("/v1/qualification-runs"));
    }

    @Test
    void tcgaLungAcquisitionIsOpenAccessPatientDisjointAndQuotaBounded() throws Exception {
        var script = Files.readString(Path.of("scripts/acquire-tcga-lung-he.ps1"));

        assertTrue(script.contains("TCGA-LUAD"));
        assertTrue(script.contains("TCGA-LUSC"));
        assertTrue(script.contains("access';value=@('open')"));
        assertTrue(script.contains("$sourceLimit = 45GB"));
        assertTrue(script.contains("$selected.Count -ne 40"));
        assertTrue(script.contains("@($selected.patient | Sort-Object -Unique).Count -ne 40"));
        assertTrue(script.contains("Get-FileHash -LiteralPath $partial -Algorithm MD5"));
        assertTrue(script.contains("Algorithm SHA256"));
        assertTrue(script.contains("PathLabTcgaLungAcquisition"));
        assertFalse(script.contains("HF_TOKEN"));
    }

    @Test
    void tcgaLungCohortIsTissueAwareCoordinateBoundAndRightsPinned() throws Exception {
        var script = Files.readString(Path.of("scripts/build-tcga-lung-dinov2-cohort.ps1"));

        assertTrue(script.contains("tcga-luad-lusc-he-20x2-v1"));
        assertTrue(script.contains("$derivedQuotaBytes = 25GB"));
        assertTrue(script.contains("NIH-GDS/NCI-GDC-open-access-policy"));
        assertTrue(script.contains("Get-TissueCoordinate"));
        assertTrue(script.contains("vipsheader.exe"));
        assertTrue(script.contains("vips.exe"));
        assertTrue(script.contains("tile-cache.json"));
        assertTrue(script.contains("sourceGroup=[string]$row.sourceGroup"));
        assertTrue(script.contains("maximumSourceOverlap=0"));
        assertTrue(script.contains("LYMPH_NODE_AND_OOD_GROUPS_ABSENT"));
        assertFalse(script.contains("Invoke-WebRequest"));
        assertFalse(script.contains("Invoke-RestMethod"));
    }

    @Test
    void tcgaLungCampaignRequiresNewReportAdapterAndExactCohortBinding() throws Exception {
        var script = Files.readString(Path.of("scripts/stage-tcga-lung-dinov2-retrieval.ps1"));

        assertTrue(script.contains("2.1.3"));
        assertTrue(script.contains("qualificationCohortManifest = $cohortPath"));
        assertTrue(script.contains("qualificationCohortManifestSha256 = $cohortSha"));
        assertTrue(script.contains("@($cohort.samples).Count -ne 40"));
        assertTrue(script.contains("/v1/qualification-runs"));
        assertTrue(script.contains("campaignTargetMet"));
    }

    @Test
    void tcgaCohortBuildCanWaitAutonomouslyForAcquisition() throws Exception {
        var script = Files.readString(Path.of("scripts/start-tcga-lung-cohort-build.ps1"));

        assertTrue(script.contains("PathLabTcgaLungCohortBuild"));
        assertTrue(script.contains("build-tcga-lung-dinov2-cohort.ps1"));
        assertTrue(script.contains("RepetitionInterval (New-TimeSpan -Minutes 5)"));
        assertTrue(script.contains("if ($acquisition.state -ne 'completed')"));
        assertTrue(script.contains("Write-Status 'completed'"));
    }
}
