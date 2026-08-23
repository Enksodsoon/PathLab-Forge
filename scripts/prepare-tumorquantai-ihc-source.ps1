[CmdletBinding()]
param(
    [ValidateSet('Prepare','Status')] [string] $Action = 'Prepare',
    [string] $StateRoot = 'D:\PathLabData\EvidenceMentor\state'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$recordId = '21797920'
$datasetId = 'tumorquantai-breast-ihc-manifest-v1'
$bundleName = 'TQA_BreastIHC_manifest_bundle.zip'
$bundleMd5 = 'e85d64ab3d37f94469a6c507ef3fea88'
$maximumBundleBytes = 2MB
$state = [IO.Path]::GetFullPath($StateRoot)
$root = Join-Path $state "acquisition\$datasetId"
$statusPath = Join-Path $root 'status.json'
$recordPath = Join-Path $root 'zenodo-record.json'
$bundlePath = Join-Path $root $bundleName
$extractRoot = [IO.Path]::GetFullPath((Join-Path $root 'manifest')).TrimEnd('\')

function Write-JsonAtomic([string] $Path, [object] $Value) {
    New-Item -ItemType Directory -Path (Split-Path -Parent $Path) -Force | Out-Null
    $partial = "$Path.partial"
    [IO.File]::WriteAllText($partial, (($Value | ConvertTo-Json -Depth 20) + "`n"),
        [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $partial -Destination $Path -Force
}

function Write-Status([string] $StateValue, [long] $Completed, [long] $Total, [string] $Detail) {
    Write-JsonAtomic $statusPath ([ordered]@{
        schema='pathlab.acquisition-status/1';datasetId=$datasetId;state=$StateValue
        completedBytes=$Completed;totalBytes=$Total;detail=$Detail
        networkContext='interactive-user-acquisition-only';analysisNetwork='disabled'
        updatedAt=[DateTimeOffset]::UtcNow.ToString('o')
    })
}

if ($Action -eq 'Status') {
    if (Test-Path -LiteralPath $statusPath -PathType Leaf) { Get-Content -LiteralPath $statusPath -Raw }
    else { '{"schema":"pathlab.acquisition-status/1","state":"not_started"}' }
    return
}

New-Item -ItemType Directory -Path $root -Force | Out-Null
Write-Status 'validating' 0 $maximumBundleBytes 'Validating the pinned public metadata bundle.'
$record = Invoke-RestMethod -Uri "https://zenodo.org/api/records/$recordId" -TimeoutSec 60
if ([string]$record.id -ne $recordId -or $record.metadata.doi -ne '10.5281/zenodo.21797920' -or
        $record.metadata.license.id -ne 'cc-by-4.0') {
    throw 'TumorQuantAI Zenodo identity or dataset license changed.'
}
$bundle = @($record.files | Where-Object key -eq $bundleName)
if ($bundle.Count -ne 1 -or $bundle[0].checksum -ne "md5:$bundleMd5" -or
        [long]$bundle[0].size -le 0 -or [long]$bundle[0].size -gt $maximumBundleBytes) {
    throw 'TumorQuantAI manifest bundle identity, checksum, or bounded size changed.'
}
Write-JsonAtomic $recordPath $record
$expectedBytes = [long]$bundle[0].size
if (-not (Test-Path -LiteralPath $bundlePath -PathType Leaf) -or
        (Get-Item -LiteralPath $bundlePath).Length -ne $expectedBytes -or
        (Get-FileHash -LiteralPath $bundlePath -Algorithm MD5).Hash.ToLowerInvariant() -ne $bundleMd5) {
    $partial = "$bundlePath.partial"
    Remove-Item -LiteralPath $partial -Force -ErrorAction SilentlyContinue
    Write-Status 'transferring' 0 $expectedBytes 'Downloading the pinned metadata bundle.'
    $url = "https://zenodo.org/api/records/$recordId/files/$([Uri]::EscapeDataString($bundleName))/content"
    & (Get-Command curl.exe -ErrorAction Stop).Source '--fail' '--location' '--silent' '--show-error' `
        '--remove-on-error' '--output' $partial $url
    if ($LASTEXITCODE -ne 0) { throw "TumorQuantAI manifest download failed with curl exit $LASTEXITCODE." }
    if ((Get-Item -LiteralPath $partial).Length -ne $expectedBytes -or
            (Get-FileHash -LiteralPath $partial -Algorithm MD5).Hash.ToLowerInvariant() -ne $bundleMd5) {
        throw 'TumorQuantAI manifest bundle checksum validation failed.'
    }
    Move-Item -LiteralPath $partial -Destination $bundlePath -Force
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$partialExtract = "$extractRoot.partial"
Remove-Item -LiteralPath $partialExtract -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $partialExtract -Force | Out-Null
$archive = [IO.Compression.ZipFile]::OpenRead($bundlePath)
try {
    foreach ($entry in $archive.Entries) {
        if ([string]::IsNullOrWhiteSpace($entry.Name)) { continue }
        $target = [IO.Path]::GetFullPath((Join-Path $partialExtract $entry.FullName))
        if (-not $target.StartsWith($extractRoot + '.partial' + [IO.Path]::DirectorySeparatorChar,
                [StringComparison]::OrdinalIgnoreCase)) {
            throw 'TumorQuantAI manifest archive contains an unsafe path.'
        }
        New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $true)
    }
} finally { $archive.Dispose() }
if (Test-Path -LiteralPath $extractRoot) { Remove-Item -LiteralPath $extractRoot -Recurse -Force }
Move-Item -LiteralPath $partialExtract -Destination $extractRoot
$sha = (Get-FileHash -LiteralPath $bundlePath -Algorithm SHA256).Hash.ToLowerInvariant()
Write-JsonAtomic (Join-Path $root 'rights-and-integrity.json') ([ordered]@{
    schema='pathlab.source-rights-review/1';datasetId=$datasetId
    source='Zenodo record 21797920';doi='10.5281/zenodo.21797920'
    datasetLicense='CC-BY-4.0';softwareAndModelsSeparate=$true
    permittedUse='private-research-descriptive-only';bundleSha256=$sha
    clinicalGroundTruth=$false;crossSectionCellCorrespondence=$false
    qualificationStatus='not_evaluable';reason='CASE_SUBSET_AND_REFERENCE_PROTOCOL_REVIEW_REQUIRED'
})
Write-Status 'manifest_review_required' $expectedBytes $expectedBytes `
    'Pinned manifest ready; no case archive is approved or downloaded yet.'
Get-Content -LiteralPath $statusPath -Raw
