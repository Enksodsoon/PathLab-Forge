[CmdletBinding()]
param(
    [string] $StateRoot = 'D:\PathLabData\EvidenceMentor\state',
    [ValidateRange(20, 100)]
    [int] $SamplesPerClass = 20,
    [string] $FrozenAt = '2026-08-23T03:02:46Z'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$classes = @('ADI', 'BACK', 'DEB', 'LYM', 'MUC', 'MUS', 'NORM', 'STR', 'TUM')
$state = [IO.Path]::GetFullPath($StateRoot)
$sourceRoot = [IO.Path]::GetFullPath((Join-Path $state 'sources\nct-crc-he-1214456'))
$derivedRoot = [IO.Path]::GetFullPath((Join-Path $state 'derived'))
$cohortId = "nct-crc-gi-$($SamplesPerClass)-per-class-v1"
$outputRoot = [IO.Path]::GetFullPath((Join-Path $derivedRoot "he-dinov2-small-v1\$cohortId"))
$partialRoot = "$outputRoot.partial"
$reservationRoot = Join-Path $state 'quota\reservations\derived'
$reservationPath = Join-Path $reservationRoot "build-$cohortId.reservation"
$derivedQuotaBytes = 25GB
$estimatedBytes = [long](2 * $classes.Count * $SamplesPerClass) * 1MB

try {
    [void][DateTimeOffset]::ParseExact($FrozenAt, 'yyyy-MM-ddTHH:mm:ssZ',
        [Globalization.CultureInfo]::InvariantCulture,
        [Globalization.DateTimeStyles]::AssumeUniversal)
} catch {
    throw 'FrozenAt must be an immutable UTC timestamp such as 2026-08-23T03:02:46Z.'
}
if (-not $outputRoot.StartsWith($derivedRoot + [IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase)) {
    throw 'NCT-CRC cohort path escaped the derived-data root.'
}

function Get-Sha256([string] $Path) {
    (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}
function Get-StreamSha256([IO.Stream] $Stream) {
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($algorithm.ComputeHash($Stream))).Replace('-', '').ToLowerInvariant() }
    finally { $algorithm.Dispose() }
}
function Write-JsonAtomic([string] $Path, [object] $Value, [int] $Depth = 16) {
    $partial = "$Path.partial"
    [IO.File]::WriteAllText($partial, (($Value | ConvertTo-Json -Depth $Depth) + "`n"),
        [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $partial -Destination $Path
}
function Get-TreeBytes([string] $Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Container)) { return 0L }
    $total = 0L
    Get-ChildItem -LiteralPath $Path -Recurse -File | ForEach-Object { $total += [long]$_.Length }
    return $total
}

$sourceLedgerPath = Join-Path $sourceRoot 'sample-ledger.jsonl'
if (-not (Test-Path -LiteralPath $sourceLedgerPath -PathType Leaf)) {
    throw 'The checksum-verified NCT-CRC source ledger is unavailable.'
}
$sourceLedger = @(Get-Content -LiteralPath $sourceLedgerPath | ForEach-Object { $_ | ConvertFrom-Json })
$archives = @(
    [pscustomobject]@{ Name='NCT-CRC-HE-100K.zip'; Split='reference'; Prefix='NCT-CRC-HE-100K/' },
    [pscustomobject]@{ Name='CRC-VAL-HE-7K.zip'; Split='query'; Prefix='CRC-VAL-HE-7K/' }
)
foreach ($archive in $archives) {
    $archive | Add-Member -NotePropertyName Path -NotePropertyValue (Join-Path $sourceRoot $archive.Name)
    $ledger = @($sourceLedger | Where-Object sampleId -eq ([IO.Path]::GetFileNameWithoutExtension($archive.Name)))
    if ($ledger.Count -ne 1 -or -not (Test-Path -LiteralPath $archive.Path -PathType Leaf) -or
            (Get-Item -LiteralPath $archive.Path).Length -ne [long]$ledger[0].bytes -or
            (Get-Sha256 $archive.Path) -ne [string]$ledger[0].sha256 -or
            $ledger[0].license -ne 'CC-BY-4.0' -or $ledger[0].permittedUse -ne 'private-research') {
        throw "NCT-CRC source rights, size, or checksum changed: $($archive.Name)"
    }
    $archive | Add-Member -NotePropertyName Sha256 -NotePropertyValue ([string]$ledger[0].sha256)
}
if (Test-Path -LiteralPath $outputRoot) {
    $cohortPath = Join-Path $outputRoot 'cohort.json'
    if (-not (Test-Path -LiteralPath $cohortPath -PathType Leaf)) {
        throw 'The existing NCT-CRC cohort is incomplete and requires review.'
    }
    Write-Output "Existing immutable cohort: $cohortPath"
    Write-Output "SHA256: $(Get-Sha256 $cohortPath)"
    return
}
if (Test-Path -LiteralPath $partialRoot) {
    throw 'A partial NCT-CRC cohort requires review before retrying.'
}

$usedBytes = Get-TreeBytes $derivedRoot
$reservedBytes = 0L
if (Test-Path -LiteralPath $reservationRoot -PathType Container) {
    Get-ChildItem -LiteralPath $reservationRoot -File -ErrorAction SilentlyContinue | ForEach-Object {
        $reservedBytes += [long]([IO.File]::ReadAllText($_.FullName).Trim())
    }
}
if ($usedBytes -gt $derivedQuotaBytes -or $estimatedBytes -gt ($derivedQuotaBytes - $usedBytes - $reservedBytes)) {
    throw 'The 25 GB derived-data quota cannot reserve the NCT-CRC cohort.'
}
New-Item -ItemType Directory -Path $reservationRoot -Force | Out-Null
[IO.File]::WriteAllText("$reservationPath.partial", [string]$estimatedBytes,
    [Text.UTF8Encoding]::new($false))
Move-Item -LiteralPath "$reservationPath.partial" -Destination $reservationPath

Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.IO.Compression.FileSystem
$samples = [Collections.Generic.List[object]]::new()
$ledgerLines = [Collections.Generic.List[string]]::new()
try {
    New-Item -ItemType Directory -Path (Join-Path $partialRoot 'samples') -Force | Out-Null
    foreach ($archive in $archives) {
        $zip = [IO.Compression.ZipFile]::OpenRead($archive.Path)
        try {
            foreach ($class in $classes) {
                $prefix = "$($archive.Prefix)$class/"
                $selected = @($zip.Entries | Where-Object {
                    $_.Length -gt 0 -and $_.FullName.StartsWith($prefix, [StringComparison]::Ordinal) -and
                    $_.FullName.EndsWith('.tif', [StringComparison]::OrdinalIgnoreCase)
                } | Sort-Object FullName | Select-Object -First $SamplesPerClass)
                if ($selected.Count -ne $SamplesPerClass) {
                    throw "$($archive.Name) lacks $SamplesPerClass deterministic samples for $class."
                }
                foreach ($entry in $selected) {
                    $entryStream = $entry.Open()
                    try { $entrySha = Get-StreamSha256 $entryStream }
                    finally { $entryStream.Dispose() }
                    $sampleId = "$($archive.Split)-$class-$([IO.Path]::GetFileNameWithoutExtension($entry.Name))".ToLowerInvariant()
                    $sampleRoot = Join-Path $partialRoot "samples\$sampleId"
                    New-Item -ItemType Directory -Path $sampleRoot -Force | Out-Null
                    $sourcePath = Join-Path $sampleRoot 'source.png'
                    $decodeStream = $entry.Open()
                    try {
                        $decoded = [Drawing.Image]::FromStream($decodeStream, $true, $true)
                        try {
                            if ($decoded.Width -ne 224 -or $decoded.Height -ne 224) {
                                throw "Unexpected NCT-CRC patch geometry: $($entry.FullName)"
                            }
                            $canvas = [Drawing.Bitmap]::new(512, 512, [Drawing.Imaging.PixelFormat]::Format24bppRgb)
                            try {
                                $graphics = [Drawing.Graphics]::FromImage($canvas)
                                try {
                                    $graphics.CompositingMode = [Drawing.Drawing2D.CompositingMode]::SourceCopy
                                    $graphics.CompositingQuality = [Drawing.Drawing2D.CompositingQuality]::HighQuality
                                    $graphics.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
                                    $graphics.PixelOffsetMode = [Drawing.Drawing2D.PixelOffsetMode]::HighQuality
                                    $graphics.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::HighQuality
                                    $graphics.DrawImage($decoded, [Drawing.Rectangle]::new(0, 0, 512, 512))
                                } finally { $graphics.Dispose() }
                                $canvas.Save("$sourcePath.partial", [Drawing.Imaging.ImageFormat]::Png)
                            } finally { $canvas.Dispose() }
                        } finally { $decoded.Dispose() }
                    } finally { $decodeStream.Dispose() }
                    Move-Item -LiteralPath "$sourcePath.partial" -Destination $sourcePath
                    $sourceSha = Get-Sha256 $sourcePath
                    $sampleManifestPath = Join-Path $sampleRoot 'sample.json'
                    Write-JsonAtomic $sampleManifestPath ([ordered]@{
                        schema = 'pathlab.evidence-sample/1'
                        source = "Zenodo-1214456/$($archive.Name)/$($entry.FullName)"
                        patientGroup = 'not-provided-by-public-patch-release'
                        slideId = "$($archive.Split):$sampleId"
                        sha256 = $sourceSha
                        license = 'CC-BY-4.0'
                        taskLabel = "gi-$($class.ToLowerInvariant())"
                        permittedUse = 'private-research'
                        bytes = (Get-Item -LiteralPath $sourcePath).Length
                        grandfatheredReadOnly = $false
                    })
                    $revision = "nct-crc:$($archive.Split):$entrySha"
                    $tileManifestPath = Join-Path $sampleRoot 'tile-cache.json'
                    Write-JsonAtomic $tileManifestPath ([ordered]@{
                        schema = 'pathlab.tile-cache/1'
                        source = [ordered]@{
                            sha256 = $sourceSha; slideRevision = $revision; width = 512; height = 512
                            sampleManifest = 'sample.json'; sampleManifestSha256 = Get-Sha256 $sampleManifestPath
                        }
                        tilePixels = 512; encoding = 'png'; preprocessingInput = 'rgb-srgb-uint8'
                        tiles = @([ordered]@{
                            id = 'tile-001'; path = 'source.png'; sha256 = $sourceSha
                            x = 0; y = 0; width = 512; height = 512
                        })
                    })
                    $samples.Add([ordered]@{
                        id = $sampleId
                        split = $archive.Split
                        evaluationGroup = 'gi'
                        phenotypeGroup = $class
                        sourceGroup = $archive.Name
                        archiveSha256 = $archive.Sha256
                        archiveEntry = $entry.FullName
                        archiveEntrySha256 = $entrySha
                        tileCacheManifest = "samples/$sampleId/tile-cache.json"
                        tileCacheManifestSha256 = Get-Sha256 $tileManifestPath
                    })
                    $ledgerLines.Add(([ordered]@{
                        sampleId = $sampleId; source = "Zenodo record 1214456/$($archive.Name)"
                        patientGroup = 'not-provided-by-public-patch-release'; slideGroup = $sampleId
                        sha256 = $entrySha; license = 'CC-BY-4.0'; permittedUse = 'private-research'
                        task = 'he-retrieval-qualification'; taskLabel = "gi-$($class.ToLowerInvariant())"
                        split = $archive.Split; sourceGroup = $archive.Name
                    } | ConvertTo-Json -Compress))
                }
            }
        } finally { $zip.Dispose() }
    }
    [IO.File]::WriteAllLines((Join-Path $partialRoot 'sample-ledger.jsonl'), $ledgerLines,
        [Text.UTF8Encoding]::new($false))
    Write-JsonAtomic (Join-Path $partialRoot 'cohort.json') ([ordered]@{
        schema = 'pathlab.qualification-cohort/1'
        cohortId = $cohortId
        frozenAt = $FrozenAt
        intendedUse = 'private-research-model-qualification'
        executionStatus = 'ready'
        qualificationStatus = 'not_evaluable'
        qualificationReasons = @(
            'PATIENT_LEVEL_PATCH_MAPPING_UNAVAILABLE',
            'BREAST_LUNG_LYMPH_NODE_AND_OOD_GROUPS_ABSENT'
        )
        transform = [ordered]@{
            sourcePixels = 224; outputPixels = 512; method = 'high-quality-bicubic-rgb'
        }
        acceptanceCriteria = [ordered]@{
            requiredEvaluationGroups = @('breast', 'gi', 'lung', 'lymph-node', 'benign-reactive')
            minimumReferenceSamplesPerGroup = 20; minimumQuerySamplesPerGroup = 20
            minimumOodSamples = 20; maximumPatientOverlap = 0; maximumSlideOverlap = 0
            maximumSourceOverlap = 0; baselineId = 'color-histogram-v1'
            minimumMacroRecallAt5Improvement = 0.05
            minimumMacroNdcgAt10Improvement = 0.03; minimumOodAuRoc = 0.80
        }
        samples = $samples
    })
    $actualBytes = Get-TreeBytes $partialRoot
    if ($actualBytes -gt $estimatedBytes) { throw 'NCT-CRC cohort exceeded its derived-data reservation.' }
    New-Item -ItemType Directory -Path (Split-Path -Parent $outputRoot) -Force | Out-Null
    Move-Item -LiteralPath $partialRoot -Destination $outputRoot
    Remove-Item -LiteralPath $reservationPath -Force
} catch {
    throw
}

$cohortPath = Join-Path $outputRoot 'cohort.json'
Write-Output "Built immutable NCT-CRC GI execution cohort: $cohortPath"
Write-Output "Samples: $($samples.Count)"
Write-Output "SHA256: $(Get-Sha256 $cohortPath)"
Write-Output 'Overall H&E qualification remains NOT_EVALUABLE until patient mapping and all frozen tissue/OOD groups exist.'
