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
        assertTrue(script.contains("qualification-prepared"));
        assertTrue(script.contains("$DerivedUsedBytes"));
        assertTrue(script.contains("$reservationPath = \"$outputRoot.reservation\""));
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
    void tcgaLungRemediationIsLabelBlindQcBoundedAndKeepsFrozenGates() throws Exception {
        var script = Files.readString(Path.of("scripts/build-tcga-lung-dinov2-cohort.ps1"));
        var protocol = Files.readString(Path.of(
                "docs/evidence/tcga-lung-tile-remediation-protocol-v1.md"));

        assertTrue(script.contains("tcga-luad-lusc-lung-20x2-qc-remediation-v1"));
        assertTrue(script.contains("Get-TissueCandidates"));
        assertTrue(script.contains("Get-TileQcScore"));
        assertTrue(script.contains("Select-Object -First 9"));
        assertTrue(script.contains("minimumMacroNdcgAt10Improvement=0.03"));
        assertFalse(protocol.toLowerCase().contains("lower the"));
        assertTrue(protocol.contains("0.03"));
        assertTrue(protocol.contains("label-blind"));
        assertTrue(protocol.contains("one allowed remediation"));
    }

    @Test
    void tcgaLungCampaignRequiresNewReportAdapterAndExactCohortBinding() throws Exception {
        var script = Files.readString(Path.of("scripts/stage-tcga-lung-dinov2-retrieval.ps1"));

        assertTrue(script.contains("2.1.4"));
        assertTrue(script.contains("qualificationCohortManifest = $cohortPath"));
        assertTrue(script.contains("qualificationCohortManifestSha256 = $cohortSha"));
        assertTrue(script.contains("@($cohort.samples).Count -ne 40"));
        assertTrue(script.contains("/v1/qualification-runs"));
        assertTrue(script.contains("campaignTargetMet"));
        assertTrue(script.contains("ReadAndExecute"));
        assertTrue(script.contains("SetSecurityDescriptorSddlForm"));
        assertTrue(script.contains("$CohortId"));
        assertTrue(script.contains("$ProtocolPath"));
    }

    @Test
    void tcgaCohortBuildCanWaitAutonomouslyForAcquisition() throws Exception {
        var script = Files.readString(Path.of("scripts/start-tcga-lung-cohort-build.ps1"));

        assertTrue(script.contains("PathLabTcgaLungCohortBuild"));
        assertTrue(script.contains("PathLabTcgaLungCohortRemediation"));
        assertTrue(script.contains("build-tcga-lung-dinov2-cohort.ps1"));
        assertTrue(script.contains("RepetitionInterval (New-TimeSpan -Minutes 5)"));
        assertTrue(script.contains("if ($acquisition.state -ne 'completed')"));
        assertTrue(script.contains("Write-Status 'completed'"));
        assertTrue(script.contains("SetSecurityDescriptorSddlForm"));
        assertTrue(script.indexOf("Set-Acl -LiteralPath $derivedRoot -AclObject $derivedAcl")
                < script.indexOf("Test-Path -LiteralPath $cohortPath -PathType Leaf"),
                "The protected cohort must not be probed before the temporary read/write handoff");
    }

    @Test
    void tumorQuantIhcPreparationIsPinnedBoundedAndNonQualifying() throws Exception {
        var script = Files.readString(Path.of("scripts/prepare-tumorquantai-ihc-source.ps1"));

        assertTrue(script.contains("21797920"));
        assertTrue(script.contains("10.5281/zenodo.21797920"));
        assertTrue(script.contains("cc-by-4.0"));
        assertTrue(script.contains("TQA_BreastIHC_manifest_bundle.zip"));
        assertTrue(script.contains("e85d64ab3d37f94469a6c507ef3fea88"));
        assertTrue(script.contains("2MB"), "Manifest preparation must stay bounded");
        assertTrue(script.contains("manifest_review_required"));
        assertTrue(script.contains("private-research-descriptive-only"));
        assertTrue(script.contains("ZipFile]::OpenRead"));
        assertTrue(script.contains("StartsWith($extractRoot"), "Archive extraction must reject traversal");
    }

    @Test
    void tumorQuantIhcSubsetIsAutonomousCaseDisjointAndChecksumBounded() throws Exception {
        var script = Files.readString(Path.of("scripts/acquire-tumorquantai-ihc-subset.ps1"));

        assertTrue(script.contains("tumorquantai-breast-ihc-4case-v1"));
        assertTrue(script.contains("PathLabTumorQuantIhcAcquisition"));
        assertTrue(script.contains("$sourceLimit = 45GB"));
        assertTrue(script.contains("TQA_BC_ZPVYVY4T27UKEAYPKAOX.zip"));
        assertTrue(script.contains("TQA_BC_KHLKIB6TVKGYE7SUWAOX.zip"));
        assertTrue(script.contains("TQA_BC_2R5PE76UT27ESW6WXFR7.zip"));
        assertTrue(script.contains("TQA_BC_5QIEJCI66QT6FMUHJ67O.zip"));
        assertTrue(script.contains("upstreamSha256"));
        assertTrue(script.contains("patientGroup = $item.case"));
        assertTrue(script.contains("private-research-descriptive-only"));
        assertTrue(script.contains("for ($attempt = 1; $attempt -le 3; $attempt++)"));
        assertTrue(script.contains("$requiredBytes += [long]$item.bytes"));
        assertTrue(script.contains("service-managed source quota reservation root"));
        assertTrue(script.contains("$chunkBytes = 8MB"));
        assertTrue(script.contains("'--range'"));
        assertTrue(script.contains("TumorQuantAI assembled checksum mismatch"));
        assertTrue(script.contains("$null -eq $sum -or $null -eq $sum.Sum"));
        assertTrue(script.contains(".Length-eq$expectedChunkBytes){Move-Item"));
        assertTrue(script.contains("-Destination $chunk -Force"));
        assertFalse(script.contains("$exitCode-ne 0-or-not(Test-Path $chunk)"));
        assertFalse(script.contains("RepetitionInterval"), "A failed three-attempt transfer must not restart forever");
        assertFalse(script.contains("--continue-at"));
    }

    @Test
    void tumorQuantIhcFixtureCampaignWaitsBuildsAndSubmitsAutonomously() throws Exception {
        var script = Files.readString(Path.of("scripts/stage-tumorquantai-ihc-fixture-campaign.ps1"));

        assertTrue(script.contains("PathLabTumorQuantIhcFixtureCampaign"));
        assertTrue(script.contains("tumorquantai-ihc-descriptive-execution-20260823-v2"));
        assertTrue(script.contains("if($acquisition.state-ne'completed')"));
        assertTrue(script.contains("RepetitionInterval (New-TimeSpan -Minutes 5)"));
        assertTrue(script.contains("@('ER','PR','Ki-67','HER2')"));
        assertTrue(script.contains("markerIdentitySource='import-metadata'"));
        assertTrue(script.contains("controlsValidated=$false"));
        assertTrue(script.contains("SetSecurityDescriptorSddlForm"));
        assertTrue(script.contains("/v1/qualification-runs"));
        assertTrue(script.contains("private-research-descriptive-only"));
        assertTrue(script.contains("crossSectionCellCorrespondence=$false"));
        assertTrue(script.contains("-RepositoryRoot `\"$repository`\""));
        assertTrue(script.contains("$taskAction=New-ScheduledTaskAction"));
        assertTrue(script.contains("relativePath=\"$sampleId/preview.png\""));
        assertTrue(script.contains("Join-Path $outputRoot $sample.relativePath"));
        assertTrue(script.contains("Final IHC fixture is unavailable"));
    }

    @Test
    void monusacAcquisitionIsOfficialRestrictedResumableAndNonQualifying() throws Exception {
        var script = Files.readString(Path.of("scripts/acquire-monusac2020.ps1"));

        assertTrue(script.contains("monusac2020-official-v1"));
        assertTrue(script.contains("PathLabMonusacAcquisition"));
        assertTrue(script.contains("$sourceLimit = 45GB"));
        assertTrue(script.contains("1lxMZaAPSpEHLSxGA9KKMt_r-4S8dwLhq"));
        assertTrue(script.contains("545564883L"));
        assertTrue(script.contains("1G54vsOdxWY1hG7dzmkeK3r0xz9s-heyQ"));
        assertTrue(script.contains("202746703L"));
        assertTrue(script.contains("1kdOl3s6uQBRv0nToSIf1dPuceZunzL4N"));
        assertTrue(script.contains("19590377L"));
        assertTrue(script.contains("CC-BY-NC-SA-4.0"));
        assertTrue(script.contains("private-research-restricted"));
        assertTrue(script.contains("upstreamChecksumAvailable=$false"));
        assertTrue(script.contains("atlasCleanEligible=$false"));
        assertTrue(script.contains("Get-FileHash $partial -Algorithm SHA256"));
        assertTrue(script.contains("for ($attempt = 1; $attempt -le 3; $attempt++)"));
        assertTrue(script.contains("$chunkBytes = 8MB"));
        assertTrue(script.contains("'--range'"));
        assertTrue(script.contains("Content-Range"));
        assertTrue(script.contains("bytes $offset-$end/$($item.bytes)"));
        assertTrue(script.contains("source-integrity-frozen-after-first-acquisition"));
        assertTrue(script.contains("https://monusac-2020.grand-challenge.org/Data/"));
        assertTrue(script.contains("https://drive.usercontent.google.com/download"));
        assertFalse(script.contains("RepetitionInterval"));
        assertFalse(script.contains("--continue-at"));
    }

    @Test
    void monusacCohortIsPatientHeldOutFourOrganAndChecksumBounded() throws Exception {
        var script = Files.readString(Path.of("scripts/build-monusac-cell-cohort.ps1"));

        assertTrue(script.contains("monusac2020-cell-heldout-23-v1"));
        assertTrue(script.contains("$derivedQuotaBytes = 25GB"));
        assertTrue(script.contains("5b7cbeb34817a8f880d3fddc28391e48d3329a91bf3adcbd131ea149a725cd92"));
        assertTrue(script.contains("bcbc38f6bf8b149230c90c29f3428cc7b2b76f8acd7766ce9fc908fc896c2674"));
        assertTrue(script.contains("2e4e7774559595a77ed57387ff47177a1da65ccff6154bc2c5d311c8ef02a878"));
        assertTrue(script.contains("TCGA-MP-A4T7"));
        assertTrue(script.contains("TCGA-A2-A0ES"));
        assertTrue(script.contains("patientOverlapWithTraining=$false"));
        assertTrue(script.contains("@($samples).Count -ne 23"));
        assertTrue(script.contains("CC-BY-NC-SA-4.0"));
        assertTrue(script.contains("private-research-restricted"));
        assertTrue(script.contains("ZipArchiveMode]::Read"));
        assertTrue(script.contains("Find-VipsBinary 'vips.exe'"));
        assertTrue(script.contains("Get-FileHash -LiteralPath"));
        assertTrue(script.contains("qualification-held-out-test"));
        assertFalse(script.contains("Invoke-WebRequest"));
        assertFalse(script.contains("Invoke-RestMethod"));
    }

    @Test
    void monusacCampaignBindsExactCohortAndFrozenCellGates() throws Exception {
        var script = Files.readString(Path.of("scripts/stage-monusac-cell-qualification.ps1"));

        assertTrue(script.contains("monusac-od-watershed-heldout-20260824-v1"));
        assertTrue(script.contains("qualificationCohortManifest = $cohortPath"));
        assertTrue(script.contains("qualificationCohortManifestSha256 = $cohortSha"));
        assertTrue(script.contains("minimumMacroPq -ne 0.45"));
        assertTrue(script.contains("minimumInstanceDice -ne 0.70"));
        assertTrue(script.contains("maximumCountError -ne 0.15"));
        assertTrue(script.contains("maximumMorphometryBias -ne 0.10"));
        assertTrue(script.contains("maximumFailedRegionRate -ne 0.05"));
        assertTrue(script.contains("SetSecurityDescriptorSddlForm"));
        assertTrue(script.contains("/v1/qualification-runs"));
        assertTrue(script.contains("private-research-restricted"));
    }
}
