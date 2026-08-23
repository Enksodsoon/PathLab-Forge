[CmdletBinding()]
param(
    [string] $StateRoot = 'D:\PathLabData\EvidenceMentor\state',
    [string] $FrozenAt = '2026-08-23T05:00:00Z'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$datasetId = 'tcga-luad-lusc-he-20x2-v1'
$cohortId = 'tcga-luad-lusc-lung-20x2-v1'
$state = [IO.Path]::GetFullPath($StateRoot)
$sourceRoot = [IO.Path]::GetFullPath((Join-Path $state "sources\$datasetId"))
$acquisitionRoot = Join-Path $state "acquisition\$datasetId"
$derivedRoot = [IO.Path]::GetFullPath((Join-Path $state 'derived'))
$outputRoot = [IO.Path]::GetFullPath((Join-Path $derivedRoot "he-dinov2-small-v1\$cohortId"))
$partialRoot = "$outputRoot.partial"
$reservationRoot = Join-Path $state 'quota\reservations\derived'
$reservationPath = Join-Path $reservationRoot "build-$cohortId.reservation"
$derivedQuotaBytes = 25GB
$estimatedBytes = 160MB

function Get-Sha256([string] $Path) {
    (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}
function Write-JsonAtomic([string] $Path, [object] $Value, [int] $Depth = 20) {
    $partial = "$Path.partial"
    [IO.File]::WriteAllText($partial, (($Value | ConvertTo-Json -Depth $Depth) + "`n"),
        [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $partial -Destination $Path
}
function Get-TreeBytes([string] $Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Container)) { return 0L }
    $sum = 0L
    Get-ChildItem -LiteralPath $Path -Recurse -File | ForEach-Object { $sum += [long]$_.Length }
    return $sum
}
function Find-VipsBinary([string] $Name) {
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($null -ne $command) { return $command.Source }
    $packageRoot = Join-Path $env:LOCALAPPDATA 'Microsoft\WinGet\Packages\libvips.libvips_Microsoft.Winget.Source_8wekyb3d8bbwe'
    $candidate = Get-ChildItem -LiteralPath $packageRoot -Filter $Name -Recurse -File -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending | Select-Object -First 1
    if ($null -eq $candidate) { throw "The pinned local libvips runtime is unavailable: $Name" }
    return $candidate.FullName
}
function Invoke-Vips([string] $Executable, [string[]] $Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "libvips failed with exit code $LASTEXITCODE." }
}
function Get-TissueCoordinate([string] $OverviewPath, [int] $SourceWidth, [int] $SourceHeight) {
    $bitmap = [Drawing.Bitmap]::new($OverviewPath)
    try {
        $bestX = [int]($bitmap.Width / 2); $bestY = [int]($bitmap.Height / 2); $best = -1.0
        for ($y = 2; $y -lt $bitmap.Height - 2; $y += 3) {
            for ($x = 2; $x -lt $bitmap.Width - 2; $x += 3) {
                $pixel = $bitmap.GetPixel($x, $y)
                $max = [Math]::Max($pixel.R, [Math]::Max($pixel.G, $pixel.B))
                $min = [Math]::Min($pixel.R, [Math]::Min($pixel.G, $pixel.B))
                $luminance = 0.2126 * $pixel.R + 0.7152 * $pixel.G + 0.0722 * $pixel.B
                if ($luminance -lt 35 -or $luminance -gt 245) { continue }
                $score = ($max - $min) + 0.20 * (245 - $luminance)
                if ($score -gt $best) { $best = $score; $bestX = $x; $bestY = $y }
            }
        }
        if ($best -lt 0) { throw 'No tissue-like location was found in the WSI overview.' }
        $centerX = [long][Math]::Round(($bestX + 0.5) * $SourceWidth / $bitmap.Width)
        $centerY = [long][Math]::Round(($bestY + 0.5) * $SourceHeight / $bitmap.Height)
        return [pscustomobject]@{
            X = [int][Math]::Max(0, [Math]::Min($SourceWidth - 512, $centerX - 256))
            Y = [int][Math]::Max(0, [Math]::Min($SourceHeight - 512, $centerY - 256))
        }
    } finally { $bitmap.Dispose() }
}

try {
    [void][DateTimeOffset]::ParseExact($FrozenAt, 'yyyy-MM-ddTHH:mm:ssZ',
        [Globalization.CultureInfo]::InvariantCulture,
        [Globalization.DateTimeStyles]::AssumeUniversal)
} catch { throw 'FrozenAt must be an immutable UTC timestamp.' }
if (-not $outputRoot.StartsWith($derivedRoot + [IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase)) { throw 'TCGA cohort path escaped the derived-data root.' }

$statusPath = Join-Path $acquisitionRoot 'status.json'
$gdcPath = Join-Path $sourceRoot 'gdc-files.json'
$ledgerPath = Join-Path $sourceRoot 'sample-ledger.jsonl'
foreach ($required in @($statusPath, $gdcPath, $ledgerPath)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "TCGA acquisition is incomplete: $required" }
}
$status = Get-Content -LiteralPath $statusPath -Raw | ConvertFrom-Json
$gdc = Get-Content -LiteralPath $gdcPath -Raw | ConvertFrom-Json
$rows = @(Get-Content -LiteralPath $ledgerPath | ForEach-Object { $_ | ConvertFrom-Json })
if ($status.state -ne 'completed' -or $gdc.schema -ne 'pathlab.gdc-acquisition-manifest/1' -or
        @($gdc.files).Count -ne 40 -or $rows.Count -ne 40 -or
        @($rows.patientGroup | Sort-Object -Unique).Count -ne 40 -or
        @($rows | Where-Object split -eq 'reference').Count -ne 20 -or
        @($rows | Where-Object split -eq 'query').Count -ne 20 -or
        @($rows | Where-Object license -ne 'NIH-GDS/NCI-GDC-open-access-policy').Count -ne 0 -or
        @($rows | Where-Object permittedUse -ne 'private-research').Count -ne 0) {
    throw 'The frozen TCGA source ledger is incomplete, overlapping, or unapproved.'
}
if (Test-Path -LiteralPath $outputRoot -PathType Container) {
    $cohortPath = Join-Path $outputRoot 'cohort.json'
    if (-not (Test-Path -LiteralPath $cohortPath -PathType Leaf)) { throw 'Existing TCGA cohort is incomplete.' }
    Write-Output "Existing immutable cohort: $cohortPath"
    Write-Output "SHA256: $(Get-Sha256 $cohortPath)"
    return
}
if (Test-Path -LiteralPath $partialRoot) { throw 'A partial TCGA cohort requires review before retrying.' }

$used = Get-TreeBytes $derivedRoot
$reserved = 0L
if (Test-Path -LiteralPath $reservationRoot -PathType Container) {
    Get-ChildItem -LiteralPath $reservationRoot -Filter '*.reservation' -File -ErrorAction SilentlyContinue |
        Where-Object FullName -ne $reservationPath | ForEach-Object {
            $reserved += [long]([IO.File]::ReadAllText($_.FullName).Trim())
        }
}
if ($estimatedBytes -gt $derivedQuotaBytes - $used - $reserved) {
    throw 'The 25 GB derived-data quota cannot reserve the TCGA lung cohort.'
}
New-Item -ItemType Directory -Path $reservationRoot -Force | Out-Null
[IO.File]::WriteAllText("$reservationPath.partial", [string]$estimatedBytes, [Text.UTF8Encoding]::new($false))
Move-Item -LiteralPath "$reservationPath.partial" -Destination $reservationPath

$vips = Find-VipsBinary 'vips.exe'
$vipsHeader = Find-VipsBinary 'vipsheader.exe'
Add-Type -AssemblyName System.Drawing
$samples = [Collections.Generic.List[object]]::new()
try {
    New-Item -ItemType Directory -Path (Join-Path $partialRoot 'samples') -Force | Out-Null
    foreach ($row in @($rows | Sort-Object split, taskLabel, patientGroup, sampleId)) {
        $sourceName = ([string]$row.source).Substring(([string]$row.source).LastIndexOf('/') + 1)
        $source = Join-Path $sourceRoot $sourceName
        if (-not (Test-Path -LiteralPath $source -PathType Leaf) -or
                (Get-Item -LiteralPath $source).Length -ne [long]$row.bytes -or
                (Get-Sha256 $source) -ne [string]$row.sha256) {
            throw "TCGA source checksum changed: $sourceName"
        }
        $width = [int]((& $vipsHeader -f width $source).Trim())
        $height = [int]((& $vipsHeader -f height $source).Trim())
        if ($LASTEXITCODE -ne 0 -or $width -lt 512 -or $height -lt 512) {
            throw "TCGA source geometry is unsupported: $sourceName"
        }
        $sampleId = ([string]$row.sampleId).ToLowerInvariant()
        $sampleRoot = Join-Path $partialRoot "samples\$sampleId"
        New-Item -ItemType Directory -Path $sampleRoot -Force | Out-Null
        $overview = Join-Path $sampleRoot 'overview.png'
        Invoke-Vips $vips @('thumbnail', $source, $overview, '2048', '--size', 'both')
        $coordinate = Get-TissueCoordinate $overview $width $height
        Remove-Item -LiteralPath $overview -Force
        $tilePath = Join-Path $sampleRoot 'source.png'
        Invoke-Vips $vips @('crop', $source, "$tilePath.partial.png", [string]$coordinate.X,
            [string]$coordinate.Y, '512', '512')
        Move-Item -LiteralPath "$tilePath.partial.png" -Destination $tilePath
        $tileSha = Get-Sha256 $tilePath
        $sampleManifestPath = Join-Path $sampleRoot 'sample.json'
        Write-JsonAtomic $sampleManifestPath ([ordered]@{
            schema='pathlab.evidence-sample/1'; source="$($row.source)#wsi-sha256=$($row.sha256)"
            patientGroup=[string]$row.patientGroup; slideId=[string]$row.slideGroup
            sha256=$tileSha; license='NIH-GDS/NCI-GDC-open-access-policy'
            taskLabel=[string]$row.taskLabel; permittedUse='private-research'
            bytes=[long](Get-Item -LiteralPath $tilePath).Length; grandfatheredReadOnly=$false
        })
        $tileManifestPath = Join-Path $sampleRoot 'tile-cache.json'
        Write-JsonAtomic $tileManifestPath ([ordered]@{
            schema='pathlab.tile-cache/1'
            source=[ordered]@{
                sha256=$tileSha; slideRevision="gdc:$($row.sampleId):$($row.sha256)"
                width=$width; height=$height; sampleManifest='sample.json'
                sampleManifestSha256=Get-Sha256 $sampleManifestPath
            }
            tilePixels=512; encoding='png'; preprocessingInput='rgb-srgb-uint8'
            tiles=@([ordered]@{
                id='tile-001'; path='source.png'; sha256=$tileSha; x=$coordinate.X; y=$coordinate.Y
                width=512; height=512
            })
        })
        $samples.Add([ordered]@{
            id=$sampleId; tileCacheManifest="samples/$sampleId/tile-cache.json"
            tileCacheManifestSha256=Get-Sha256 $tileManifestPath; evaluationGroup='lung'
            phenotypeGroup=[string]$row.taskLabel; split=[string]$row.split
            sourceGroup=[string]$row.sourceGroup
        })
    }
    Write-JsonAtomic (Join-Path $partialRoot 'cohort.json') ([ordered]@{
        schema='pathlab.qualification-cohort/1'; cohortId=$cohortId; frozenAt=$FrozenAt
        intendedUse='private-research-model-qualification'
        acceptanceCriteria=[ordered]@{
            requiredEvaluationGroups=@('breast','gi','lung','lymph-node','benign-reactive')
            minimumReferenceSamplesPerGroup=20; minimumQuerySamplesPerGroup=20
            minimumOodSamples=20; maximumPatientOverlap=0; maximumSlideOverlap=0
            maximumSourceOverlap=0; baselineId='color-histogram-v1'
            minimumMacroRecallAt5Improvement=0.05; minimumMacroNdcgAt10Improvement=0.03
            minimumOodAuRoc=0.80
        }
        samples=$samples.ToArray()
    })
    if ((Get-TreeBytes $partialRoot) -gt $estimatedBytes) { throw 'TCGA cohort exceeded its reservation.' }
    New-Item -ItemType Directory -Path (Split-Path -Parent $outputRoot) -Force | Out-Null
    Move-Item -LiteralPath $partialRoot -Destination $outputRoot
    Remove-Item -LiteralPath $reservationPath -Force
} catch { throw }

$cohortPath = Join-Path $outputRoot 'cohort.json'
Write-Output "Built immutable TCGA lung cohort: $cohortPath"
Write-Output "Samples: $($samples.Count)"
Write-Output "SHA256: $(Get-Sha256 $cohortPath)"
Write-Output 'Overall H&E qualification remains NOT_EVALUABLE: BREAST_GI_BENIGN_REACTIVE_LYMPH_NODE_AND_OOD_GROUPS_ABSENT from this standalone cohort; fusion occurs only in a separately frozen evidence set.'
