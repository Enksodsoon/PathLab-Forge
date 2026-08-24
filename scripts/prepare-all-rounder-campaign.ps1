[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $PreparationManifest,
    [string] $StateRoot = 'D:\PathLabData\EvidenceMentor\state'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$allowedHosts = @(
    'huggingface.co', 'cdn-lfs.huggingface.co', 'api.gdc.cancer.gov',
    'portal.gdc.cancer.gov', 'zenodo.org', 'www.ebi.ac.uk'
)
$limits = @{ source = 45GB; derived = 25GB; models = 10GB; evidence = 10GB }
$bucketDirectories = @{ source = 'sources'; derived = 'derived'; models = 'models'; evidence = 'artifacts' }

function Require-ExactFields($Value, [string[]] $Names, [string] $Label) {
    $actual = @($Value.PSObject.Properties.Name | Sort-Object)
    $expected = @($Names | Sort-Object)
    if (($actual -join "`n") -ne ($expected -join "`n")) { throw "$Label fields are invalid." }
}

function File-Sha256([string] $Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

$preparationPath = [IO.Path]::GetFullPath($PreparationManifest)
if (-not (Test-Path -LiteralPath $preparationPath -PathType Leaf)) {
    throw 'Preparation manifest is unavailable.'
}
$spec = Get-Content -LiteralPath $preparationPath -Raw | ConvertFrom-Json
Require-ExactFields $spec @('schema','campaignManifest','sampleLedger','artifacts') 'Preparation manifest'
if ($spec.schema -ne 'pathlab.campaign-preparation/1') { throw 'Preparation schema is unsupported.' }
$base = Split-Path -Parent $preparationPath
$campaignPath = [IO.Path]::GetFullPath((Join-Path $base $spec.campaignManifest))
$ledgerPath = [IO.Path]::GetFullPath((Join-Path $base $spec.sampleLedger))
if (-not $campaignPath.StartsWith($base, [StringComparison]::OrdinalIgnoreCase) -or
        -not $ledgerPath.StartsWith($base, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Preparation inputs must stay inside the preparation directory.'
}
if (-not (Test-Path -LiteralPath $campaignPath -PathType Leaf) -or
        -not (Test-Path -LiteralPath $ledgerPath -PathType Leaf)) {
    throw 'Frozen campaign manifest or sample ledger is unavailable.'
}

$usage = @{ source = 0L; derived = 0L; models = 0L; evidence = 0L }
foreach ($artifact in @($spec.artifacts)) {
    Require-ExactFields $artifact @('id','url','sha256','destination','bucket','license','permittedUse','gated') 'Acquisition artifact'
    if ($artifact.id -notmatch '^[A-Za-z0-9._-]{1,120}$' -or $artifact.sha256 -notmatch '^[a-f0-9]{64}$') {
        throw 'Acquisition artifact identity is invalid.'
    }
    if (-not $limits.ContainsKey([string]$artifact.bucket) -or
            [string]::IsNullOrWhiteSpace($artifact.license) -or
            $artifact.permittedUse -notin @('private-research','research-restricted','benchmark-only')) {
        throw 'Acquisition artifact rights or quota bucket is invalid.'
    }
    $uri = [Uri]$artifact.url
    if ($uri.Scheme -ne 'https' -or $uri.Host -notin $allowedHosts) {
        throw "Acquisition source is not allowlisted: $($uri.Host)"
    }
    $bucketRoot = Join-Path $StateRoot $bucketDirectories[[string]$artifact.bucket]
    $destination = [IO.Path]::GetFullPath((Join-Path $bucketRoot ([string]$artifact.destination)))
    if (-not $destination.StartsWith([IO.Path]::GetFullPath($bucketRoot), [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Acquisition destination escapes its quota bucket.'
    }
    New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
    if (-not (Test-Path -LiteralPath $destination -PathType Leaf)) {
        $headers = @{}
        if ([bool]$artifact.gated) {
            if ([string]::IsNullOrWhiteSpace($env:HF_TOKEN)) { throw "Gated terms/token required for $($artifact.id)." }
            $headers.Authorization = "Bearer $env:HF_TOKEN"
        }
        $partial = "$destination.partial"
        try { Invoke-WebRequest -UseBasicParsing -Uri $uri -Headers $headers -OutFile $partial }
        catch { Remove-Item -LiteralPath $partial -Force -ErrorAction SilentlyContinue; throw }
        if ((File-Sha256 $partial) -ne $artifact.sha256) {
            Remove-Item -LiteralPath $partial -Force
            throw "Checksum mismatch for $($artifact.id)."
        }
        Move-Item -LiteralPath $partial -Destination $destination
    } elseif ((File-Sha256 $destination) -ne $artifact.sha256) {
        throw "Existing artifact checksum mismatch for $($artifact.id)."
    }
    $usage[[string]$artifact.bucket] += (Get-Item -LiteralPath $destination).Length
}
foreach ($bucket in $limits.Keys) {
    if ($usage[$bucket] -gt $limits[$bucket]) { throw "$bucket acquisition quota exceeded." }
}

$sampleCount = 0
foreach ($line in Get-Content -LiteralPath $ledgerPath) {
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    $sample = $line | ConvertFrom-Json
    Require-ExactFields $sample @('sampleId','source','patientGroup','slideGroup','sha256','license','permittedUse','task','split') 'Sample ledger record'
    if ($sample.sha256 -notmatch '^[a-f0-9]{64}$' -or [string]::IsNullOrWhiteSpace($sample.patientGroup) -or
            [string]::IsNullOrWhiteSpace($sample.slideGroup) -or [string]::IsNullOrWhiteSpace($sample.license)) {
        throw 'Sample provenance is incomplete; candidate is NOT_EVALUABLE.'
    }
    $sampleCount++
}
if ($sampleCount -lt 1) { throw 'Sample ledger is empty; campaign is NOT_EVALUABLE.' }

$campaign = Get-Content -LiteralPath $campaignPath -Raw | ConvertFrom-Json
if ($campaign.schema -ne 'pathlab.qualification-campaign/1' -or $campaign.maxRemediationAttempts -ne 1) {
    throw 'Frozen campaign manifest is invalid.'
}
$freeze = [ordered]@{
    schema = 'pathlab.campaign-freeze/1'
    campaignManifest = $campaignPath
    campaignManifestSha256 = File-Sha256 $campaignPath
    sampleLedgerSha256 = File-Sha256 $ledgerPath
    sampleCount = $sampleCount
    acquiredBytes = $usage
    frozenAt = [DateTimeOffset]::UtcNow.ToString('o')
    credentialsPersisted = $false
}
$freezePath = Join-Path $base 'campaign-freeze.json'
[IO.File]::WriteAllText("$freezePath.partial", (($freeze | ConvertTo-Json -Depth 8) + "`n"),
    [Text.UTF8Encoding]::new($false))
Move-Item -LiteralPath "$freezePath.partial" -Destination $freezePath -Force
Write-Host "Campaign frozen: $freezePath"
Write-Host 'Credentials were used only by this interactive acquisition process and were not persisted.'
