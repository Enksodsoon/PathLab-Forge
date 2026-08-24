[CmdletBinding()]
param(
    [string] $DatasetRoot = 'D:\PathLabData\BRACS',
    [string] $StateRoot = 'D:\PathLabData\EvidenceMentor\state',
    [string] $FrozenAt = '2026-08-24T06:30:00Z',
    [long] $DerivedUsedBytes = -1
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$cohortId = 'bracs-roi-breast-retrieval-20x2-v1'
$derivedQuotaBytes = 25GB
$estimatedBytes = 100MB
$state = [IO.Path]::GetFullPath($StateRoot)
$derivedRoot = [IO.Path]::GetFullPath((Join-Path $state 'derived'))
$outputRoot = [IO.Path]::GetFullPath((Join-Path $derivedRoot "qualification-prepared\$cohortId"))
$partialRoot = "$outputRoot.partial"
$reservationPath = "$outputRoot.reservation"
$manifestPath = Join-Path $DatasetRoot 'prepared\bracs-roi-clean-v1\manifest.csv'
$provenancePath = Join-Path $DatasetRoot 'prepared\bracs-roi-clean-v1\provenance.json'
$rawRoot = [IO.Path]::GetFullPath((Join-Path $DatasetRoot 'raw\BRACS_RoI\latest_version'))

function Sha256([string] $Path) {
    (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}
function Write-JsonAtomic([string] $Path, [object] $Value) {
    [IO.File]::WriteAllText("$Path.partial", (($Value | ConvertTo-Json -Depth 20) + "`n"),
        [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath "$Path.partial" -Destination $Path
}
function Get-TreeBytes([string] $Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Container)) { return 0L }
    $sum = 0L
    Get-ChildItem -LiteralPath $Path -File -Recurse | ForEach-Object { $sum += [long]$_.Length }
    return $sum
}
function Select-BoundedRows([object[]] $Rows, [string] $Split, [hashtable] $Quotas) {
    $selected = [Collections.Generic.List[object]]::new()
    foreach ($label in @($Quotas.Keys | Sort-Object)) {
        $eligible = @($Rows | Where-Object { $_.split -eq $Split -and $_.label -eq $label } |
            Sort-Object patient_id,slide_id,roi_id)
        $chosen = [Collections.Generic.List[object]]::new()
        $patients = @{}
        foreach ($row in $eligible) {
            if ($patients.ContainsKey([string]$row.patient_id)) { continue }
            $patients[[string]$row.patient_id] = $true; $chosen.Add($row)
            if ($chosen.Count -eq [int]$Quotas[$label]) { break }
        }
        if ($chosen.Count -lt [int]$Quotas[$label]) {
            $chosenIds = @{}; $chosen | ForEach-Object { $chosenIds[[string]$_.roi_id] = $true }
            foreach ($row in $eligible) {
                if ($chosenIds.ContainsKey([string]$row.roi_id)) { continue }
                $chosen.Add($row)
                if ($chosen.Count -eq [int]$Quotas[$label]) { break }
            }
        }
        if ($chosen.Count -ne [int]$Quotas[$label]) { throw "BRACS $Split lacks bounded samples for $label." }
        $chosen | ForEach-Object { $selected.Add($_) }
    }
    return $selected.ToArray()
}
function Get-WindowScore([Drawing.Bitmap] $Image, [int] $X, [int] $Y) {
    $tissue = 0; $contrast = 0.0; $count = 0
    for ($py=$Y; $py -lt $Y+512; $py+=16) {
        for ($px=$X; $px -lt $X+512; $px+=16) {
            $pixel=$Image.GetPixel($px,$py); $count++
            $maximum=[Math]::Max($pixel.R,[Math]::Max($pixel.G,$pixel.B))
            $minimum=[Math]::Min($pixel.R,[Math]::Min($pixel.G,$pixel.B))
            $luminance=0.2126*$pixel.R+0.7152*$pixel.G+0.0722*$pixel.B
            if ($luminance -ge 30 -and $luminance -le 240 -and ($maximum-$minimum) -ge 8) {
                $tissue++; $contrast += $maximum-$minimum
            }
        }
    }
    return 100.0*$tissue/[Math]::Max(1,$count)+$contrast/[Math]::Max(1,$tissue)
}

try { [void][DateTimeOffset]::Parse($FrozenAt) } catch { throw 'FrozenAt must be an immutable timestamp.' }
if (-not $outputRoot.StartsWith($derivedRoot + [IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase)) { throw 'BRACS cohort path escaped the derived-data root.' }
foreach ($required in @($manifestPath,$provenancePath,$rawRoot)) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Reviewed BRACS input is unavailable: $required" }
}
$provenance = Get-Content -LiteralPath $provenancePath -Raw | ConvertFrom-Json
if ($provenance.dataset -ne 'BRACS_RoI_latest_version' -or
        $provenance.license_declared_by_current_source -ne 'CC-BY-NC-4.0' -or
        -not $provenance.patient_disjoint -or (Sha256 $manifestPath) -ne $provenance.manifest_sha256) {
    throw 'BRACS provenance, rights, split isolation, or manifest checksum changed.'
}
if (Test-Path -LiteralPath $outputRoot -PathType Container) {
    $existing = Join-Path $outputRoot 'cohort.json'
    if (-not (Test-Path -LiteralPath $existing -PathType Leaf)) { throw 'Existing BRACS cohort is incomplete.' }
    Write-Output "Existing immutable cohort: $existing"; Write-Output "SHA256: $(Sha256 $existing)"; return
}
if ((Test-Path -LiteralPath $partialRoot) -or (Test-Path -LiteralPath $reservationPath)) {
    throw 'A partial BRACS breast cohort requires review.'
}
$used = if ($DerivedUsedBytes -ge 0) { $DerivedUsedBytes } else { Get-TreeBytes $derivedRoot }
if ($estimatedBytes -gt $derivedQuotaBytes-$used) { throw 'The 25 GB derived-data quota cannot reserve BRACS.' }
[IO.File]::WriteAllText("$reservationPath.partial", [string]$estimatedBytes, [Text.UTF8Encoding]::new($false))
Move-Item -LiteralPath "$reservationPath.partial" -Destination $reservationPath

$rows = @(Import-Csv -LiteralPath $manifestPath | Where-Object {
    $_.mode -eq 'RGB' -and [int]$_.width -ge 512 -and [int]$_.height -ge 512
})
$breastQuotas = @{ADH=5;DCIS=5;FEA=5;IC=5}
$benignQuotas = @{N=7;PB=7;UDH=6}
$reference = @((Select-BoundedRows $rows 'val' $breastQuotas) + (Select-BoundedRows $rows 'val' $benignQuotas))
$query = @((Select-BoundedRows $rows 'test' $breastQuotas) + (Select-BoundedRows $rows 'test' $benignQuotas))
if ($reference.Count -ne 40 -or $query.Count -ne 40 -or
        @($reference.patient_id | Where-Object { $_ -in $query.patient_id }).Count -ne 0) {
    throw 'BRACS reference/query selection is not patient-disjoint 40 by 40.'
}

Add-Type -AssemblyName System.Drawing
$samples = [Collections.Generic.List[object]]::new()
try {
    New-Item -ItemType Directory -Path (Join-Path $partialRoot 'samples') -Force | Out-Null
    foreach ($entry in @(@($reference | ForEach-Object { [pscustomobject]@{row=$_;split='reference'} }) +
            @($query | ForEach-Object { [pscustomobject]@{row=$_;split='query'} }))) {
        $row=$entry.row; $split=$entry.split
        $source=[IO.Path]::GetFullPath((Join-Path $rawRoot ([string]$row.relative_path)))
        if (-not $source.StartsWith($rawRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or
                -not (Test-Path -LiteralPath $source -PathType Leaf) -or
                (Get-Item -LiteralPath $source).Length -ne [long]$row.file_size_bytes -or
                (Sha256 $source) -ne [string]$row.sha256) { throw "BRACS source changed: $($row.roi_id)" }
        $sampleId=([string]$row.roi_id).ToLowerInvariant().Replace('_','-')
        $sampleRoot=Join-Path $partialRoot "samples\$sampleId"
        New-Item -ItemType Directory -Path $sampleRoot -Force | Out-Null
        $image=[Drawing.Bitmap]::new($source)
        try {
            if ($image.Width -ne [int]$row.width -or $image.Height -ne [int]$row.height) {
                throw "BRACS decoded geometry changed: $($row.roi_id)"
            }
            $xs=@(0,[int](($image.Width-512)/2),($image.Width-512))|Sort-Object -Unique
            $ys=@(0,[int](($image.Height-512)/2),($image.Height-512))|Sort-Object -Unique
            $candidates=foreach($y in $ys){foreach($x in $xs){[pscustomobject]@{X=$x;Y=$y;Score=Get-WindowScore $image $x $y}}}
            $chosen=@($candidates|Sort-Object @{Expression='Score';Descending=$true},Y,X)[0]
            $tile=$image.Clone([Drawing.Rectangle]::new($chosen.X,$chosen.Y,512,512),
                [Drawing.Imaging.PixelFormat]::Format24bppRgb)
            try { $tile.Save((Join-Path $sampleRoot 'source.png'),[Drawing.Imaging.ImageFormat]::Png) } finally { $tile.Dispose() }
        } finally { $image.Dispose() }
        $tilePath=Join-Path $sampleRoot 'source.png'; $tileSha=Sha256 $tilePath
        $sampleManifest=Join-Path $sampleRoot 'sample.json'
        Write-JsonAtomic $sampleManifest ([ordered]@{
            schema='pathlab.evidence-sample/1';source="BRACS_RoI_latest_version#$($row.sha256)"
            patientGroup=[string]$row.patient_id;slideId=[string]$row.slide_id;sha256=$tileSha
            license='CC-BY-NC-4.0';taskLabel="breast-$(([string]$row.label).ToLowerInvariant())"
            permittedUse='private-research';bytes=[long](Get-Item $tilePath).Length;grandfatheredReadOnly=$true
        })
        $tileManifest=Join-Path $sampleRoot 'tile-cache.json'
        Write-JsonAtomic $tileManifest ([ordered]@{
            schema='pathlab.tile-cache/1';source=[ordered]@{
                sha256=$tileSha;slideRevision="bracs-roi:$($row.roi_id):$($row.sha256)"
                width=[int]$row.width;height=[int]$row.height;sampleManifest='sample.json'
                sampleManifestSha256=Sha256 $sampleManifest
            };tilePixels=512;encoding='png';preprocessingInput='rgb-srgb-uint8'
            tiles=@([ordered]@{id='tile-001';path='source.png';sha256=$tileSha;x=$chosen.X;y=$chosen.Y;width=512;height=512})
        })
        $evaluationGroup=if($row.label -in @('N','PB','UDH')){'benign-reactive'}else{'breast'}
        $samples.Add([ordered]@{
            id=$sampleId;tileCacheManifest="samples/$sampleId/tile-cache.json"
            tileCacheManifestSha256=Sha256 $tileManifest;evaluationGroup=$evaluationGroup
            phenotypeGroup=[string]$row.label;split=$split;sourceGroup='bracs-roi-single-source'
        })
    }
    if (@($samples | Where-Object split -eq 'reference').Count -ne 40 -or
            @($samples | Where-Object split -eq 'query').Count -ne 40) { throw 'BRACS cohort sample counts changed.' }
    Write-JsonAtomic (Join-Path $partialRoot 'cohort.json') ([ordered]@{
        schema='pathlab.qualification-cohort/1';cohortId=$cohortId;frozenAt=$FrozenAt
        intendedUse='private-research-model-qualification';qualificationStatus='not_evaluable'
        qualificationReasons=@('SOURCE_HELD_OUT_NOT_MET_SINGLE_BRACS_RELEASE','LYMPH_NODE_GI_LUNG_AND_OOD_GROUPS_ABSENT')
        acceptanceCriteria=[ordered]@{
            requiredEvaluationGroups=@('breast','gi','lung','lymph-node','benign-reactive')
            minimumReferenceSamplesPerGroup=20;minimumQuerySamplesPerGroup=20;minimumOodSamples=20
            maximumPatientOverlap=0;maximumSlideOverlap=0;maximumSourceOverlap=0
            baselineId='color-histogram-v1';minimumMacroRecallAt5Improvement=0.05
            minimumMacroNdcgAt10Improvement=0.03;minimumOodAuRoc=0.80
        };samples=$samples.ToArray()
    })
    if ((Get-TreeBytes $partialRoot) -gt $estimatedBytes) { throw 'BRACS cohort exceeded its reservation.' }
    New-Item -ItemType Directory -Path (Split-Path -Parent $outputRoot) -Force | Out-Null
    Move-Item -LiteralPath $partialRoot -Destination $outputRoot
    Remove-Item -LiteralPath $reservationPath -Force
} catch { throw }
$cohortPath=Join-Path $outputRoot 'cohort.json'
Write-Output "Built immutable BRACS breast retrieval cohort: $cohortPath"
Write-Output 'Samples: 80 (40 reference, 40 query)'
Write-Output "SHA256: $(Sha256 $cohortPath)"
Write-Output 'Qualification remains NOT_EVALUABLE because BRACS is one source and GI, lung, lymph-node, and OOD groups are absent.'
