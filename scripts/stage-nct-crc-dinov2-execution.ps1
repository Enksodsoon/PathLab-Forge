[CmdletBinding()]
param(
    [string] $StateRoot = 'D:\PathLabData\EvidenceMentor\state',
    [string] $CampaignId = 'dinov2-nct-crc-gi-execution-20260823-v1'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repository = Split-Path -Parent $PSScriptRoot
$state = [IO.Path]::GetFullPath($StateRoot)
$cohortRoot = Join-Path $state 'derived\he-dinov2-small-v1\nct-crc-gi-20-per-class-v1'
$cohortPath = Join-Path $cohortRoot 'cohort.json'
$modelRoot = Join-Path $state 'models\he-dinov2-small-v1\1'
$packSource = Join-Path $repository 'src\main\resources\evidence-packs\he-dinov2-small-v1.json'
$packTarget = Join-Path $modelRoot 'qualification-pack.json'
$campaignParent = Join-Path $state 'acceptance\campaigns'
$campaignRoot = [IO.Path]::GetFullPath((Join-Path $campaignParent $CampaignId))
if (-not $campaignRoot.StartsWith([IO.Path]::GetFullPath($campaignParent) +
        [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'GI execution campaign path escaped the acceptance root.'
}

function Sha256([string] $Path) {
    (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}
function Write-Json([string] $Path, [object] $Value) {
    [IO.File]::WriteAllText($Path, (($Value | ConvertTo-Json -Depth 16) + "`n"),
        [Text.UTF8Encoding]::new($false))
}

if (-not (Test-Path -LiteralPath $cohortPath -PathType Leaf)) {
    throw 'The immutable NCT-CRC GI cohort is unavailable.'
}
$cohort = Get-Content -LiteralPath $cohortPath -Raw | ConvertFrom-Json
if ($cohort.schema -ne 'pathlab.qualification-cohort/1' -or
        $cohort.executionStatus -ne 'ready' -or $cohort.qualificationStatus -ne 'not_evaluable' -or
        @($cohort.samples).Count -ne 360) {
    throw 'The NCT-CRC GI cohort contract is invalid.'
}
$sample = @($cohort.samples | Where-Object {
    $_.split -eq 'query' -and $_.phenotypeGroup -eq 'TUM'
} | Sort-Object id | Select-Object -First 1)
if ($sample.Count -ne 1) { throw 'No frozen NCT-CRC query tumor sample is available.' }
$tileManifestPath = [IO.Path]::GetFullPath((Join-Path $cohortRoot ([string]$sample[0].tileCacheManifest)))
if (-not $tileManifestPath.StartsWith([IO.Path]::GetFullPath($cohortRoot) +
        [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or
        -not (Test-Path -LiteralPath $tileManifestPath -PathType Leaf) -or
        (Sha256 $tileManifestPath) -ne [string]$sample[0].tileCacheManifestSha256) {
    throw 'The selected GI tile-cache checksum or path is invalid.'
}
$tileManifest = Get-Content -LiteralPath $tileManifestPath -Raw | ConvertFrom-Json
$sampleRoot = Split-Path -Parent $tileManifestPath
$sourcePath = Join-Path $sampleRoot 'source.png'
$sampleManifestPath = Join-Path $sampleRoot ([string]$tileManifest.source.sampleManifest)
$sampleManifest = Get-Content -LiteralPath $sampleManifestPath -Raw | ConvertFrom-Json
if ((Sha256 $sourcePath) -ne [string]$tileManifest.source.sha256 -or
        (Sha256 $sampleManifestPath) -ne [string]$tileManifest.source.sampleManifestSha256 -or
        [string]$sampleManifest.sha256 -ne [string]$tileManifest.source.sha256 -or
        $sampleManifest.permittedUse -ne 'private-research') {
    throw 'The selected GI source or provenance checksum is invalid.'
}

$pack = Get-Content -LiteralPath $packSource -Raw | ConvertFrom-Json
if ($pack.schema -ne 'pathlab.ai-pack/1' -or $pack.packId -ne 'he-dinov2-small-v1' -or
        $pack.validation.status -ne 'experimental' -or $pack.rights.allowedUse -ne 'private-research') {
    throw 'The DINOv2 qualification pack is not the expected fail-closed experimental candidate.'
}
$artifactFiles = @{
    model='model.safetensors'; config='config.json'; preprocessor='preprocessor_config.json'
    worker='worker.exe'; workerSource='worker.py'; 'runtime-manifest'='runtime-manifest.json'
}
foreach ($artifact in $pack.artifacts) {
    if (-not $artifactFiles.ContainsKey([string]$artifact.name)) { continue }
    $artifactPath = Join-Path $modelRoot $artifactFiles[[string]$artifact.name]
    if (-not (Test-Path -LiteralPath $artifactPath -PathType Leaf) -or
            (Sha256 $artifactPath) -ne [string]$artifact.sha256) {
        throw "The pinned DINOv2 artifact changed: $($artifact.name)"
    }
}
Copy-Item -LiteralPath $packSource -Destination "$packTarget.partial" -Force
Move-Item -LiteralPath "$packTarget.partial" -Destination $packTarget -Force

New-Item -ItemType Directory -Path $campaignRoot -Force | Out-Null
$campaignPath = Join-Path $campaignRoot 'campaign.json'
$requestPath = Join-Path $campaignRoot 'request-he-dinov2-small-v1.json'
$attestationPath = Join-Path $campaignRoot 'attestation-he-dinov2-small-v1.json'
$protocolPath = Join-Path $repository 'docs\evidence\he-retrieval-qualification-protocol-v1.md'
$protocolSha = Sha256 $protocolPath
Write-Json $requestPath ([ordered]@{
    schema = 'pathlab.evidence-job/2'
    sourcePath = $sourcePath
    sourceSha256 = [string]$tileManifest.source.sha256
    slideRevision = [string]$tileManifest.source.slideRevision
    previewPath = $sourcePath
    sourceWidth = 512
    sourceHeight = 512
    packManifest = $packTarget
    stain = 'he'
    marker = 'generic'
    tileCacheManifest = $tileManifestPath
    tileCacheManifestSha256 = Sha256 $tileManifestPath
    qualificationCampaignManifest = $campaignPath
})
Write-Json $campaignPath ([ordered]@{
    schema = 'pathlab.qualification-campaign/1'
    campaignId = $CampaignId
    createdAt = '2026-08-23T03:02:46Z'
    researchOnly = $true
    notDiagnostic = $true
    maxRemediationAttempts = 1
    quota = [ordered]@{
        sourceBytes = 45GB; derivedBytes = 25GB; modelBytes = 10GB
        evidenceBytes = 10GB; reserveBytes = 10GB
    }
    tracks = @([ordered]@{
        id = 'he-dinov2-small-gi-execution-v1'
        candidateId = 'he-dinov2-small-v1'
        capability = 'he-evidence'
        scope = 'deployment'
        requestPath = [IO.Path]::GetFileName($requestPath)
        remediationRequestPath = $null
        expectedAttestationPath = [IO.Path]::GetFileName($attestationPath)
        protocolSha256 = $protocolSha
        dependsOn = @()
        required = $true
    })
})
$ledgerPath = Join-Path $campaignRoot 'sample-ledger.jsonl'
$ledger = [ordered]@{
    sampleId = [string]$sample[0].id
    source = [string]$sampleManifest.source
    patientGroup = [string]$sampleManifest.patientGroup
    slideGroup = [string]$sampleManifest.slideId
    sha256 = [string]$sample[0].archiveEntrySha256
    license = [string]$sampleManifest.license
    permittedUse = [string]$sampleManifest.permittedUse
    task = 'real-gi-execution-smoke-no-retrieval-claim'
    split = [string]$sample[0].split
}
[IO.File]::WriteAllText($ledgerPath, (($ledger | ConvertTo-Json -Compress) + "`n"),
    [Text.UTF8Encoding]::new($false))
$preparationPath = Join-Path $campaignRoot 'preparation.json'
Write-Json $preparationPath ([ordered]@{
    schema = 'pathlab.campaign-preparation/1'
    campaignManifest = 'campaign.json'
    sampleLedger = 'sample-ledger.jsonl'
    artifacts = @()
})
& (Join-Path $repository 'scripts\prepare-all-rounder-campaign.ps1') `
    -PreparationManifest $preparationPath -StateRoot $state

$endpoint = Get-Content -LiteralPath (Join-Path $state 'endpoint.json') -Raw | ConvertFrom-Json
if ($endpoint.serviceVersion -notin @('2.1.1', '2.1.2')) {
    throw "Service 2.1.1 or 2.1.2 is required; found $($endpoint.serviceVersion)."
}
$token = [IO.File]::ReadAllText((Join-Path $state 'ipc-token')).Trim()
$headers = @{ Authorization = "Bearer $token" }
try {
    try {
        $response = Invoke-RestMethod -Method Get `
            -Uri "http://127.0.0.1:$($endpoint.port)/v1/qualification-runs/$CampaignId" `
            -Headers $headers
    } catch {
        if ($_.Exception.Response.StatusCode -ne 404) { throw }
        $response = Invoke-RestMethod -Method Post `
            -Uri "http://127.0.0.1:$($endpoint.port)/v1/qualification-runs" `
            -Headers $headers -ContentType 'application/json' `
            -Body (@{ manifestPath = $campaignPath } | ConvertTo-Json -Compress)
    }
    $control = Invoke-RestMethod -Method Post `
        -Uri "http://127.0.0.1:$($endpoint.port)/v1/control/resume" -Headers $headers
} finally {
    Remove-Variable token, headers -ErrorAction SilentlyContinue
}
[pscustomobject]@{
    CampaignId = $response.id
    State = $response.state
    CampaignCompleted = $response.campaignCompleted
    CampaignTargetMet = $response.campaignTargetMet
    AcceptingJobs = $control.acceptingJobs
    ManifestPath = $campaignPath
    ManifestSha256 = Sha256 $campaignPath
    CohortManifestSha256 = Sha256 $cohortPath
}
