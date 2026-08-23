[CmdletBinding()]
param(
    [string]$DatasetRoot = 'D:\PathLabData\BRACS',
    [string]$StateRoot = 'D:\PathLabData\EvidenceMentor\state',
    [string]$FrozenAt = '2026-08-22T00:00:00Z',
    [ValidateRange(1, 5)][int]$SamplesPerLabel = 1,
    [ValidateRange(1, 5)][int]$TilesPerSample = 4
)

$ErrorActionPreference = 'Stop'
$derivedQuotaBytes = 25GB
$cohortId = 'bracs-roi-smoke-v1'
$manifestPath = Join-Path $DatasetRoot 'prepared\bracs-roi-clean-v1\manifest.jsonl'
$manifestCsvPath = Join-Path $DatasetRoot 'prepared\bracs-roi-clean-v1\manifest.csv'
$provenancePath = Join-Path $DatasetRoot 'prepared\bracs-roi-clean-v1\provenance.json'
$rawRoot = Join-Path $DatasetRoot 'raw\BRACS_RoI\latest_version'
$derivedRoot = [IO.Path]::GetFullPath((Join-Path $StateRoot 'derived'))
$outputRoot = [IO.Path]::GetFullPath((Join-Path $derivedRoot "he-dinov2-small-v1\$cohortId"))
$partialRoot = "$outputRoot.partial"

try {
    [void][DateTimeOffset]::ParseExact($FrozenAt, 'yyyy-MM-ddTHH:mm:ssZ',
        [Globalization.CultureInfo]::InvariantCulture,
        [Globalization.DateTimeStyles]::AssumeUniversal)
} catch {
    throw 'FrozenAt must be an immutable UTC timestamp such as 2026-08-22T00:00:00Z.'
}

if (-not $outputRoot.StartsWith($derivedRoot + [IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase)) {
    throw 'BRACS tile-cache path escaped the Evidence Mentor derived-data root.'
}
if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf) -or
        -not (Test-Path -LiteralPath $manifestCsvPath -PathType Leaf) -or
        -not (Test-Path -LiteralPath $provenancePath -PathType Leaf) -or
        -not (Test-Path -LiteralPath $rawRoot -PathType Container)) {
    throw 'The reviewed local BRACS ROI dataset is unavailable.'
}
if (Test-Path -LiteralPath $outputRoot) {
    $cohort = Join-Path $outputRoot 'cohort.json'
    if (-not (Test-Path -LiteralPath $cohort -PathType Leaf)) {
        throw 'The existing BRACS tile cache is incomplete and requires review.'
    }
    Write-Output "Existing immutable cohort: $cohort"
    Write-Output "SHA256: $((Get-FileHash -LiteralPath $cohort -Algorithm SHA256).Hash.ToLowerInvariant())"
    return
}
if (Test-Path -LiteralPath $partialRoot) {
    throw 'A partial BRACS tile-cache build requires review before retrying.'
}

function Get-TreeBytes([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Container)) { return 0L }
    $sum = 0L
    Get-ChildItem -LiteralPath $Path -Recurse -File | ForEach-Object {
        $sum = [long]($sum + $_.Length)
    }
    return $sum
}

$provenance = Get-Content -Raw -LiteralPath $provenancePath | ConvertFrom-Json
if ($provenance.dataset -ne 'BRACS_RoI_latest_version' -or
        $provenance.license_declared_by_current_source -ne 'CC-BY-NC-4.0' -or
        -not $provenance.patient_disjoint) {
    throw 'BRACS provenance does not match the reviewed patient-disjoint research dataset.'
}
$actualManifestSha = (Get-FileHash -LiteralPath $manifestCsvPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actualManifestSha -ne $provenance.manifest_sha256) {
    throw 'BRACS source manifest checksum does not match its provenance record.'
}

$rows = @(Get-Content -LiteralPath $manifestPath | ForEach-Object { $_ | ConvertFrom-Json } |
    Where-Object { $_.split -eq 'val' -and $_.mode -eq 'RGB' -and
        $_.width -ge 512 -and $_.height -ge 512 } |
    Sort-Object label, patient_id, slide_id, roi_id)
$selected = New-Object System.Collections.Generic.List[object]
foreach ($label in @('N', 'PB', 'UDH', 'FEA', 'ADH', 'DCIS', 'IC')) {
    $seenPatients = @{}
    foreach ($row in @($rows | Where-Object label -eq $label)) {
        if ($seenPatients.ContainsKey([string]$row.patient_id)) { continue }
        $seenPatients[[string]$row.patient_id] = $true
        $selected.Add($row)
        if (($selected | Where-Object label -eq $label).Count -ge $SamplesPerLabel) { break }
    }
    if (($selected | Where-Object label -eq $label).Count -lt $SamplesPerLabel) {
        throw "BRACS validation split lacks enough distinct patients for label $label."
    }
}

$estimatedBytes = [long]$selected.Count * $TilesPerSample * 1024 * 1024
$usedBytes = Get-TreeBytes $derivedRoot
if ($usedBytes -gt $derivedQuotaBytes -or $estimatedBytes -gt ($derivedQuotaBytes - $usedBytes)) {
    throw 'Evidence Mentor derived-data quota is exhausted.'
}
$reservationRoot = Join-Path $StateRoot 'quota\reservations\derived'
New-Item -ItemType Directory -Path $reservationRoot -Force | Out-Null
$reservation = Join-Path $reservationRoot "build-$cohortId.reservation"
$reservationPartial = "$reservation.partial"
if ((Test-Path -LiteralPath $reservation) -or (Test-Path -LiteralPath $reservationPartial)) {
    throw 'A BRACS tile-cache quota reservation already exists.'
}
[IO.File]::WriteAllText($reservationPartial, [string]$estimatedBytes)
Move-Item -LiteralPath $reservationPartial -Destination $reservation

Add-Type -AssemblyName System.Drawing
try {
    New-Item -ItemType Directory -Path $partialRoot -Force | Out-Null
    $cohortSamples = New-Object System.Collections.Generic.List[object]
    foreach ($row in $selected) {
        $source = [IO.Path]::GetFullPath((Join-Path $rawRoot ([string]$row.relative_path)))
        if (-not $source.StartsWith([IO.Path]::GetFullPath($rawRoot) + [IO.Path]::DirectorySeparatorChar,
                [StringComparison]::OrdinalIgnoreCase) -or -not (Test-Path -LiteralPath $source -PathType Leaf)) {
            throw "BRACS source escaped its reviewed root or is unavailable: $($row.roi_id)"
        }
        $sourceHash = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($sourceHash -ne $row.sha256 -or (Get-Item -LiteralPath $source).Length -ne $row.file_size_bytes) {
            throw "BRACS source checksum failed: $($row.roi_id)"
        }
        $sampleRoot = Join-Path $partialRoot "samples\$($row.roi_id)"
        $tileRoot = Join-Path $sampleRoot 'tiles'
        New-Item -ItemType Directory -Path $tileRoot -Force | Out-Null
        $sampleManifest = [ordered]@{
            schema = 'pathlab.evidence-sample/1'
            source = 'BRACS_RoI_latest_version'
            patientGroup = [string]$row.patient_id
            slideId = [string]$row.slide_id
            sha256 = $sourceHash
            license = 'CC-BY-NC-4.0 declared by reviewed BRACS source'
            taskLabel = "breast-$(([string]$row.label).ToLowerInvariant())"
            permittedUse = 'private-research'
            bytes = [long]$row.file_size_bytes
            grandfatheredReadOnly = $true
        }
        $samplePath = Join-Path $sampleRoot 'sample.json'
        [IO.File]::WriteAllText("$samplePath.partial", (($sampleManifest | ConvertTo-Json -Depth 6) + "`n"),
            [Text.UTF8Encoding]::new($false))
        Move-Item -LiteralPath "$samplePath.partial" -Destination $samplePath

        $image = New-Object Drawing.Bitmap $source
        try {
            if ($image.Width -ne $row.width -or $image.Height -ne $row.height) {
                throw "BRACS decoded geometry does not match its manifest: $($row.roi_id)"
            }
            $positions = @(
                [pscustomobject]@{ X = [int](($image.Width - 512) / 2); Y = [int](($image.Height - 512) / 2) },
                [pscustomobject]@{ X = 0; Y = 0 },
                [pscustomobject]@{ X = $image.Width - 512; Y = 0 },
                [pscustomobject]@{ X = 0; Y = $image.Height - 512 },
                [pscustomobject]@{ X = $image.Width - 512; Y = $image.Height - 512 }
            ) | Sort-Object X, Y -Unique | Select-Object -First $TilesPerSample
            $tiles = New-Object System.Collections.Generic.List[object]
            $tileIndex = 0
            foreach ($position in $positions) {
                $tileIndex++
                $tileName = 'tile-{0:D3}.png' -f $tileIndex
                $tilePath = Join-Path $tileRoot $tileName
                $rectangle = New-Object Drawing.Rectangle $position.X, $position.Y, 512, 512
                $tile = $image.Clone($rectangle, [Drawing.Imaging.PixelFormat]::Format24bppRgb)
                try {
                    $partial = "$tilePath.partial"
                    $tile.Save($partial, [Drawing.Imaging.ImageFormat]::Png)
                    Move-Item -LiteralPath $partial -Destination $tilePath
                } finally {
                    $tile.Dispose()
                }
                $tiles.Add([ordered]@{
                    id = 'tile-{0:D3}' -f $tileIndex
                    path = "tiles/$tileName"
                    sha256 = (Get-FileHash -LiteralPath $tilePath -Algorithm SHA256).Hash.ToLowerInvariant()
                    x = [int]$position.X
                    y = [int]$position.Y
                    width = 512
                    height = 512
                })
            }
        } finally {
            $image.Dispose()
        }
        $tileManifest = [ordered]@{
            schema = 'pathlab.tile-cache/1'
            source = [ordered]@{
                sha256 = $sourceHash
                slideRevision = "bracs-roi:$($row.roi_id):$sourceHash"
                width = [int]$row.width
                height = [int]$row.height
                sampleManifest = 'sample.json'
                sampleManifestSha256 = (Get-FileHash -LiteralPath $samplePath -Algorithm SHA256).Hash.ToLowerInvariant()
            }
            tilePixels = 512
            encoding = 'png'
            preprocessingInput = 'rgb-srgb-uint8'
            tiles = $tiles.ToArray()
        }
        $tileManifestPath = Join-Path $sampleRoot 'tile-cache.json'
        [IO.File]::WriteAllText("$tileManifestPath.partial", (($tileManifest | ConvertTo-Json -Depth 10) + "`n"),
            [Text.UTF8Encoding]::new($false))
        Move-Item -LiteralPath "$tileManifestPath.partial" -Destination $tileManifestPath
        $evaluationGroup = if ($row.label -in @('N', 'PB', 'UDH')) { 'benign-reactive' } else { 'breast' }
        $cohortSamples.Add([ordered]@{
            id = ([string]$row.roi_id).ToLowerInvariant().Replace('_', '-')
            tileCacheManifest = "samples/$($row.roi_id)/tile-cache.json"
            tileCacheManifestSha256 = (Get-FileHash -LiteralPath $tileManifestPath -Algorithm SHA256).Hash.ToLowerInvariant()
            evaluationGroup = $evaluationGroup
            phenotypeGroup = [string]$row.label
            split = 'query'
            sourceGroup = 'bracs-roi-current-source'
        })
    }
    $cohort = [ordered]@{
        schema = 'pathlab.qualification-cohort/1'
        cohortId = $cohortId
        frozenAt = $FrozenAt
        intendedUse = 'private-research-model-qualification'
        acceptanceCriteria = [ordered]@{
            requiredEvaluationGroups = @('breast', 'gi', 'lung', 'lymph-node', 'benign-reactive')
            minimumReferenceSamplesPerGroup = 20
            minimumQuerySamplesPerGroup = 20
            minimumOodSamples = 20
            maximumPatientOverlap = 0
            maximumSlideOverlap = 0
            maximumSourceOverlap = 0
            baselineId = 'color-histogram-v1'
            minimumMacroRecallAt5Improvement = 0.05
            minimumMacroNdcgAt10Improvement = 0.03
            minimumOodAuRoc = 0.80
        }
        samples = $cohortSamples.ToArray()
    }
    $cohortPath = Join-Path $partialRoot 'cohort.json'
    [IO.File]::WriteAllText("$cohortPath.partial", (($cohort | ConvertTo-Json -Depth 12) + "`n"),
        [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath "$cohortPath.partial" -Destination $cohortPath
    New-Item -ItemType Directory -Path (Split-Path $outputRoot) -Force | Out-Null
    Move-Item -LiteralPath $partialRoot -Destination $outputRoot
    Remove-Item -LiteralPath $reservation -Force
} catch {
    throw
}

$finalCohort = Join-Path $outputRoot 'cohort.json'
Write-Output "Built immutable BRACS breast-only smoke cohort: $finalCohort"
Write-Output "SHA256: $((Get-FileHash -LiteralPath $finalCohort -Algorithm SHA256).Hash.ToLowerInvariant())"
Write-Output 'Qualification remains NOT_EVALUABLE: reference, GI, lung, lymph-node and OOD coverage are absent.'
