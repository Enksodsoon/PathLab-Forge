[CmdletBinding()]
param(
    [string] $StateRoot = 'D:\PathLabData\EvidenceMentor\state',
    [string] $CampaignId = 'dinov2-nct-crc-gi-retrieval-20260823-v3',
    [string] $CohortManifestSha256 = 'e3fb44a4e977aeb99a1a5d0a6a97fcd8495fc14b24e66ac126e8205594bdbb38'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repository = Split-Path -Parent $PSScriptRoot
$state = [IO.Path]::GetFullPath($StateRoot)
$cohortRoot = Join-Path $state 'derived\he-dinov2-small-v1\nct-crc-gi-20-per-class-v1'
$cohortPath = Join-Path $cohortRoot 'cohort.json'
$modelRoot = Join-Path $state 'models\he-dinov2-small-v1\1'
$workerSource = Join-Path $repository 'src\main\resources\model-workers\dinov2-worker.py'
$workerTarget = Join-Path $modelRoot 'worker.py'
$packSource = Join-Path $repository 'src\main\resources\evidence-packs\he-dinov2-small-v1.json'
$packTarget = Join-Path $modelRoot 'qualification-pack.json'
$campaignParent = Join-Path $state 'acceptance\campaigns'
$campaignRoot = [IO.Path]::GetFullPath((Join-Path $campaignParent $CampaignId))

function Sha256([string] $Path) {
    (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}
function Write-JsonAtomic([string] $Path, [object] $Value) {
    $partial = "$Path.partial"
    [IO.File]::WriteAllText($partial, (($Value | ConvertTo-Json -Depth 20) + "`n"),
        [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $partial -Destination $Path -Force
}

if (-not $campaignRoot.StartsWith([IO.Path]::GetFullPath($campaignParent) +
        [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'GI retrieval campaign path escaped the acceptance root.'
}
foreach ($required in @($workerSource, $workerTarget, $packSource,
        (Join-Path $modelRoot 'worker.exe'), (Join-Path $modelRoot 'model.safetensors'),
        (Join-Path $modelRoot 'runtime-manifest.json'))) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Required input is unavailable: $required" }
}
if ($CohortManifestSha256 -notmatch '^[a-f0-9]{64}$') { throw 'The frozen cohort checksum is invalid.' }
$cohortSha = $CohortManifestSha256
$cohort = $null
try {
    $cohort = Get-Content -LiteralPath $cohortPath -Raw | ConvertFrom-Json
    if ($cohort.schema -ne 'pathlab.qualification-cohort/1' -or $cohort.executionStatus -ne 'ready' -or
            @($cohort.samples).Count -ne 360 -or (Sha256 $cohortPath) -ne $cohortSha) {
        throw 'The frozen NCT-CRC cohort contract is invalid.'
    }
} catch [System.UnauthorizedAccessException] {
    # The installed ACL deliberately delegates derived-data validation to LocalService.
}
$pack = Get-Content -LiteralPath $packSource -Raw | ConvertFrom-Json
if ($pack.packId -ne 'he-dinov2-small-v1' -or $pack.version -ne '1' -or
        $pack.runtimeCompatibility.workerProtocol -ne 'pathlab.model-worker/2' -or
        $pack.resourceEnvelope.maxSeconds -ne 1200) { throw 'The cohort worker pack contract is invalid.' }
$artifactFiles = @{
    model='model.safetensors'; config='config.json'; preprocessor='preprocessor_config.json'
    worker='worker.exe'; workerSource='worker.py'; 'runtime-manifest'='runtime-manifest.json'
}
foreach ($artifact in $pack.artifacts) {
    if (-not $artifactFiles.ContainsKey([string]$artifact.name) -or $artifact.name -eq 'workerSource') { continue }
    $path = Join-Path $modelRoot $artifactFiles[[string]$artifact.name]
    if (-not (Test-Path -LiteralPath $path -PathType Leaf) -or (Sha256 $path) -ne [string]$artifact.sha256) {
        throw "Pinned model artifact changed: $($artifact.name)"
    }
}
if ((Sha256 $workerSource) -ne [string](@($pack.artifacts | Where-Object name -eq 'workerSource')[0].sha256)) {
    throw 'Repository worker source does not match the pack ledger.'
}

$endpoint = Get-Content -LiteralPath (Join-Path $state 'endpoint.json') -Raw | ConvertFrom-Json
$token = [IO.File]::ReadAllText((Join-Path $state 'ipc-token')).Trim()
$headers = @{ Authorization = "Bearer $token" }
$origin = "http://127.0.0.1:$($endpoint.port)"
$control = Invoke-RestMethod -Method Post -Uri "$origin/v1/control/pause" -Headers $headers
$status = Invoke-RestMethod -Method Get -Uri "$origin/v1/status" -Headers $headers
if ($status.queue.active -ne 0) { throw 'A worker is active; cohort worker installation stopped safely.' }

$oldWorkerSha = Sha256 $workerTarget
$archiveRoot = Join-Path $modelRoot 'worker-source-archive'
New-Item -ItemType Directory -Path $archiveRoot -Force | Out-Null
$archivePath = Join-Path $archiveRoot "$oldWorkerSha.py"
if (-not (Test-Path -LiteralPath $archivePath -PathType Leaf)) {
    Copy-Item -LiteralPath $workerTarget -Destination "$archivePath.partial"
    Move-Item -LiteralPath "$archivePath.partial" -Destination $archivePath
}
if ((Sha256 $workerTarget) -ne (Sha256 $workerSource)) {
    Copy-Item -LiteralPath $workerSource -Destination "$workerTarget.partial" -Force
    Move-Item -LiteralPath "$workerTarget.partial" -Destination $workerTarget -Force
}
Copy-Item -LiteralPath $packSource -Destination "$packTarget.partial" -Force
Move-Item -LiteralPath "$packTarget.partial" -Destination $packTarget -Force

New-Item -ItemType Directory -Path $campaignRoot -Force | Out-Null
$campaignPath = Join-Path $campaignRoot 'campaign.json'
$requestPath = Join-Path $campaignRoot 'request-he-dinov2-small-v1.json'
$attestationPath = Join-Path $campaignRoot 'attestation-he-dinov2-small-v1.json'
$priorRequestPath = Join-Path $campaignParent `
    'dinov2-nct-crc-gi-execution-20260823-v1\request-he-dinov2-small-v1.json'
$priorRequest = Get-Content -LiteralPath $priorRequestPath -Raw | ConvertFrom-Json
$tileManifestPath = [string]$priorRequest.tileCacheManifest
$sourcePath = [string]$priorRequest.sourcePath
$protocolPath = Join-Path $repository 'docs\evidence\he-retrieval-qualification-protocol-v1.md'
$protocolSha = Sha256 $protocolPath

Write-JsonAtomic $requestPath ([ordered]@{
    schema = 'pathlab.evidence-job/2'; sourcePath = $sourcePath
    sourceSha256 = [string]$priorRequest.sourceSha256
    slideRevision = [string]$priorRequest.slideRevision; previewPath = $sourcePath
    sourceWidth = 512; sourceHeight = 512; packManifest = $packTarget; stain = 'he'
    marker = "cohort-$cohortSha"; tileCacheManifest = $tileManifestPath
    tileCacheManifestSha256 = [string]$priorRequest.tileCacheManifestSha256
    qualificationCampaignManifest = $campaignPath
})
Write-JsonAtomic $campaignPath ([ordered]@{
    schema = 'pathlab.qualification-campaign/1'; campaignId = $CampaignId
    createdAt = [DateTimeOffset]::UtcNow.ToString('o'); researchOnly = $true; notDiagnostic = $true
    maxRemediationAttempts = 1
    quota = [ordered]@{ sourceBytes=45GB; derivedBytes=25GB; modelBytes=10GB; evidenceBytes=10GB; reserveBytes=10GB }
    tracks = @([ordered]@{
        id='he-dinov2-small-gi-retrieval-v1'; candidateId='he-dinov2-small-v1'
        capability='he-evidence'; scope='deployment'; requestPath=[IO.Path]::GetFileName($requestPath)
        remediationRequestPath=$null; expectedAttestationPath=[IO.Path]::GetFileName($attestationPath)
        protocolSha256=$protocolSha; dependsOn=@(); required=$true
    })
})
$ledgerPath = Join-Path $campaignRoot 'sample-ledger.jsonl'
$ledgerLines = @(([ordered]@{
    sampleId='nct-crc-gi-20-per-class-v1'; source='Zenodo record 1214456 frozen cohort manifest'
    patientGroup='not-provided-by-public-patch-release'; slideGroup='immutable-360-patch-cohort'
    sha256=$cohortSha; license='CC-BY-4.0'; permittedUse='private-research'
    task='he-retrieval-qualification'; split='reference-and-query'
} | ConvertTo-Json -Compress))
[IO.File]::WriteAllLines($ledgerPath, $ledgerLines, [Text.UTF8Encoding]::new($false))
$preparationPath = Join-Path $campaignRoot 'preparation.json'
Write-JsonAtomic $preparationPath ([ordered]@{
    schema='pathlab.campaign-preparation/1'; campaignManifest='campaign.json'
    sampleLedger='sample-ledger.jsonl'; artifacts=@()
})
& (Join-Path $repository 'scripts\prepare-all-rounder-campaign.ps1') `
    -PreparationManifest $preparationPath -StateRoot $state

try {
    try {
        $response = Invoke-RestMethod -Method Get -Uri "$origin/v1/qualification-runs/$CampaignId" -Headers $headers
    } catch {
        if ($_.Exception.Response.StatusCode -ne 404) { throw }
        $response = Invoke-RestMethod -Method Post -Uri "$origin/v1/qualification-runs" -Headers $headers `
            -ContentType 'application/json' -Body (@{manifestPath=$campaignPath} | ConvertTo-Json -Compress)
    }
    $control = Invoke-RestMethod -Method Post -Uri "$origin/v1/control/resume" -Headers $headers
} finally {
    Remove-Variable token, headers -ErrorAction SilentlyContinue
}
[pscustomobject]@{
    CampaignId=$response.id; State=$response.state; CampaignCompleted=$response.campaignCompleted
    CampaignTargetMet=$response.campaignTargetMet; AcceptingJobs=$control.acceptingJobs
    SampleCount=360; CohortManifestSha256=$cohortSha
    WorkerSourceSha256=Sha256 $workerTarget; PackManifestSha256=Sha256 $packTarget
}
