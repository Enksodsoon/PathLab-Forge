[CmdletBinding()]
param(
    [string] $StateRoot = 'D:\PathLabData\EvidenceMentor\state',
    [string] $BracsManifest = 'D:\PathLabData\BRACS\prepared\bracs-roi-clean-v1\manifest.csv',
    [string] $BracsProvenance = 'D:\PathLabData\BRACS\prepared\bracs-roi-clean-v1\provenance.json'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repository = Split-Path -Parent $PSScriptRoot
$state = [IO.Path]::GetFullPath($StateRoot)
$campaignId = 'deterministic-baselines-20260822-v1'
$root = [IO.Path]::GetFullPath((Join-Path $state "acceptance\campaigns\$campaignId"))
if (-not $root.StartsWith((Join-Path $state 'acceptance\campaigns') + [IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase)) { throw 'Campaign path escaped the protected state root.' }

function Sha256([string] $Path) {
    (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}
function WriteJson([string] $Path, [object] $Value) {
    [IO.File]::WriteAllText($Path, (($Value | ConvertTo-Json -Depth 16) + "`n"),
        [Text.UTF8Encoding]::new($false))
}

if (-not (Test-Path -LiteralPath $BracsManifest -PathType Leaf) -or
        -not (Test-Path -LiteralPath $BracsProvenance -PathType Leaf)) {
    throw 'The grandfathered BRACS manifest and provenance are required.'
}
$provenance = Get-Content -LiteralPath $BracsProvenance -Raw | ConvertFrom-Json
if ($provenance.license_declared_by_current_source -ne 'CC-BY-NC-4.0' -or
        $provenance.patient_disjoint -ne $true) {
    throw 'BRACS rights or patient-disjoint provenance did not pass.'
}
$sample = Import-Csv -LiteralPath $BracsManifest |
    Where-Object { $_.split -eq 'test' -and $_.sha256 -match '^[a-f0-9]{64}$' } |
    Select-Object -First 1
if ($null -eq $sample) { throw 'No checksum-bound BRACS test sample is available.' }
$source = Join-Path ([string]$provenance.raw_root) ([string]$sample.relative_path)
if (-not (Test-Path -LiteralPath $source -PathType Leaf) -or (Sha256 $source) -ne $sample.sha256) {
    throw 'The selected BRACS source checksum changed.'
}

New-Item -ItemType Directory -Path $root -Force | Out-Null
$sourceTarget = Join-Path $root 'bracs-test-source.png'
if (-not (Test-Path -LiteralPath $sourceTarget)) { Copy-Item -LiteralPath $source -Destination $sourceTarget }
if ((Sha256 $sourceTarget) -ne $sample.sha256) { throw 'The staged BRACS source checksum changed.' }

$packSpecs = @(
    @{ id='cell-od-watershed-v2'; capability='cell-morphology'; stain='he'; marker='generic'; protocol='dd251775f0abe6a780b2389ee489f18bcec78b9cb80e1facfd7134a05cb0c2a0' },
    @{ id='ihc-descriptive-v2'; capability='ihc-descriptive'; stain='ihc_dab'; marker='generic'; protocol='9b2d3299eed5504fe7d89839cacae6ea5232762d6405bdfab261ad78e30b991f' },
    @{ id='special-stain-descriptive-v2'; capability='special-stain-descriptive'; stain='pas'; marker='generic'; protocol='d035efcec9812f16464b4e8e59e9d245884a57698626821ab0cf056d05854cee' },
    @{ id='cytology-descriptive-v2'; capability='cytology-descriptive'; stain='papanicolaou'; marker='generic'; protocol='b354e6c605afc55b5663167453fa2b23738f311d48de5f30293cef0ba26f962a' }
)
$campaignPath = Join-Path $root 'campaign.json'
$tracks = [Collections.Generic.List[object]]::new()
foreach ($spec in $packSpecs) {
    $packSource = Join-Path $repository "src\main\resources\evidence-packs\$($spec.id).json"
    $packTarget = Join-Path $root "$($spec.id).json"
    Copy-Item -LiteralPath $packSource -Destination $packTarget -Force
    $requestName = "request-$($spec.id).json"
    $requestPath = Join-Path $root $requestName
    WriteJson $requestPath ([ordered]@{
        schema = 'pathlab.evidence-job/2'
        sourcePath = $sourceTarget
        sourceSha256 = Sha256 $sourceTarget
        slideRevision = "bracs:$($sample.slide_id):$($sample.sha256)"
        previewPath = $sourceTarget
        sourceWidth = [int]$sample.width
        sourceHeight = [int]$sample.height
        packManifest = $packTarget
        stain = $spec.stain
        marker = $spec.marker
        qualificationCampaignManifest = $campaignPath
    })
    $tracks.Add([ordered]@{
        id = $spec.id
        candidateId = $spec.id
        capability = $spec.capability
        scope = 'deployment'
        requestPath = $requestName
        remediationRequestPath = $null
        expectedAttestationPath = "attestation-$($spec.id).json"
        protocolSha256 = $spec.protocol
        dependsOn = @()
        required = $true
    })
}
WriteJson $campaignPath ([ordered]@{
    schema = 'pathlab.qualification-campaign/1'
    campaignId = $campaignId
    createdAt = '2026-08-22T00:00:00Z'
    researchOnly = $true
    notDiagnostic = $true
    maxRemediationAttempts = 1
    quota = [ordered]@{
        sourceBytes = 45GB; derivedBytes = 25GB; modelBytes = 10GB
        evidenceBytes = 10GB; reserveBytes = 10GB
    }
    tracks = $tracks
})
$ledgerPath = Join-Path $root 'sample-ledger.jsonl'
$ledgerRecord = [ordered]@{
    sampleId = [string]$sample.roi_id
    source = 'BRACS_RoI/latest_version'
    patientGroup = [string]$sample.patient_id
    slideGroup = [string]$sample.slide_id
    sha256 = [string]$sample.sha256
    license = 'CC-BY-NC-4.0'
    permittedUse = 'research-restricted'
    task = 'deterministic-execution-smoke-only'
    split = [string]$sample.split
}
[IO.File]::WriteAllText($ledgerPath, (($ledgerRecord | ConvertTo-Json -Compress) + "`n"),
    [Text.UTF8Encoding]::new($false))
$preparationPath = Join-Path $root 'preparation.json'
WriteJson $preparationPath ([ordered]@{
    schema = 'pathlab.campaign-preparation/1'
    campaignManifest = 'campaign.json'
    sampleLedger = 'sample-ledger.jsonl'
    artifacts = @()
})

& (Join-Path $repository 'scripts\prepare-all-rounder-campaign.ps1') `
    -PreparationManifest $preparationPath -StateRoot $state

$endpoint = Get-Content -LiteralPath (Join-Path $state 'endpoint.json') -Raw | ConvertFrom-Json
$token = [IO.File]::ReadAllText((Join-Path $state 'ipc-token')).Trim()
try {
    try {
        $response = Invoke-RestMethod -Method Get `
            -Uri "http://127.0.0.1:$($endpoint.port)/v1/qualification-runs/$campaignId" `
            -Headers @{ Authorization = "Bearer $token" }
    } catch {
        if ($_.Exception.Response.StatusCode -ne 404) { throw }
        $response = Invoke-RestMethod -Method Post `
            -Uri "http://127.0.0.1:$($endpoint.port)/v1/qualification-runs" `
            -Headers @{ Authorization = "Bearer $token" } -ContentType 'application/json' `
            -Body (@{ manifestPath = $campaignPath } | ConvertTo-Json -Compress)
    }
} finally { Remove-Variable token -ErrorAction SilentlyContinue }
[pscustomobject]@{
    CampaignId = $response.id
    State = $response.state
    CampaignCompleted = $response.campaignCompleted
    CampaignTargetMet = $response.campaignTargetMet
    ManifestPath = $campaignPath
    ManifestSha256 = Sha256 $campaignPath
    DashboardLauncher = Join-Path $repository 'scripts\open-evidence-dashboard.ps1'
}
