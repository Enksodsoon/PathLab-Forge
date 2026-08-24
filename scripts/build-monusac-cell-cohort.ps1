[CmdletBinding()]
param(
    [string] $StateRoot = 'D:\PathLabData\EvidenceMentor\state',
    [string] $CohortId = 'monusac2020-cell-heldout-23-v1',
    [long] $DerivedUsedBytes = -1
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$derivedQuotaBytes = 25GB
$estimatedBytes = 512MB
$state = [IO.Path]::GetFullPath($StateRoot)
$sourceRoot = Join-Path $state 'sources\monusac2020-official-v1'
$derivedRoot = Join-Path $state 'derived'
$outputRoot = Join-Path $derivedRoot "qualification-prepared\$CohortId"
$partialRoot = "$outputRoot.partial"
$reservationPath = "$outputRoot.reservation"
$trainingArchive = Join-Path $sourceRoot 'MoNuSAC_images_and_annotations.zip'
$testingArchive = Join-Path $sourceRoot 'MoNuSAC Testing Data and Annotations.zip'
$supplement = Join-Path $sourceRoot 'MoNuSAC_Supplementary_material.pdf'
$expectedTrainingSha = '5b7cbeb34817a8f880d3fddc28391e48d3329a91bf3adcbd131ea149a725cd92'
$expectedTestingSha = 'bcbc38f6bf8b149230c90c29f3428cc7b2b76f8acd7766ce9fc908fc896c2674'
$expectedSupplementSha = '2e4e7774559595a77ed57387ff47177a1da65ccff6154bc2c5d311c8ef02a878'
$excludedTrainingOverlap = @('TCGA-MP-A4T7','TCGA-A2-A0ES')
$organPatients = [ordered]@{
    lung=@('TCGA-49-6743','TCGA-50-6591','TCGA-55-7570','TCGA-55-7573','TCGA-73-4662','TCGA-78-7152')
    kidney=@('TCGA-2Z-A9JG','TCGA-2Z-A9JN','TCGA-DW-7838','TCGA-DW-7963','TCGA-F9-A8NY','TCGA-IZ-A6M9','TCGA-MH-A55W')
    breast=@('TCGA-A2-A04X','TCGA-D8-A3Z6','TCGA-E2-A108','TCGA-EW-A6SB')
    prostate=@('TCGA-G9-6356','TCGA-G9-6367','TCGA-VP-A87E','TCGA-VP-A87H','TCGA-X4-A8KS','TCGA-YL-A9WL')
}

function Get-Sha256([string] $Path) {
    (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}
function Write-JsonAtomic([string] $Path, [object] $Value) {
    [IO.File]::WriteAllText("$Path.partial", (($Value | ConvertTo-Json -Depth 30) + "`n"),
        [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath "$Path.partial" -Destination $Path -Force
}
function Get-TreeBytes([string] $Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Container)) { return 0L }
    $sum = Get-ChildItem -LiteralPath $Path -File -Recurse | Measure-Object Length -Sum
    if ($null -eq $sum -or $null -eq $sum.Sum) { return 0L }
    return [long]$sum.Sum
}
function Find-VipsBinary([string] $Name) {
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    $candidate = Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages" -Filter $Name -File -Recurse -ErrorAction SilentlyContinue |
        Where-Object FullName -Match 'libvips' | Select-Object -First 1
    if ($candidate) { return $candidate.FullName }
    throw "$Name is required to materialize the MoNuSAC cohort."
}
function Invoke-Vips([string] $Executable, [string[]] $Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "libvips failed with exit code $LASTEXITCODE." }
}
function Copy-ZipEntry([IO.Compression.ZipArchiveEntry] $Entry, [string] $Destination) {
    $input = $Entry.Open()
    $output = [IO.File]::Open($Destination, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try { $input.CopyTo($output) } finally { $output.Dispose(); $input.Dispose() }
    if ((Get-Item -LiteralPath $Destination).Length -ne $Entry.Length) {
        throw "ZIP entry extraction size mismatch: $($Entry.FullName)"
    }
}
function Patient-FromEntry([string] $FullName) {
    $segments = $FullName -split '/'
    if ($segments.Count -lt 3 -or $segments[1].Length -lt 12) { return $null }
    return $segments[1].Substring(0,12)
}

if (-not $outputRoot.StartsWith($derivedRoot + [IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase)) { throw 'MoNuSAC cohort path escaped the derived-data root.' }
foreach ($required in @($trainingArchive,$testingArchive,$supplement)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Required MoNuSAC source is unavailable: $required" }
}
if ((Get-Sha256 $trainingArchive) -ne $expectedTrainingSha -or
        (Get-Sha256 $testingArchive) -ne $expectedTestingSha -or
        (Get-Sha256 $supplement) -ne $expectedSupplementSha) {
    throw 'A frozen MoNuSAC source checksum changed.'
}
if (Test-Path -LiteralPath $outputRoot -PathType Container) {
    $existing = Join-Path $outputRoot 'cohort.json'
    if (-not (Test-Path -LiteralPath $existing -PathType Leaf)) { throw 'Existing MoNuSAC cohort is incomplete.' }
    Write-Output "Existing immutable cohort: $existing"
    Write-Output "SHA256: $(Get-Sha256 $existing)"
    return
}
if (Test-Path -LiteralPath $partialRoot) { throw 'A partial MoNuSAC cohort requires review before retrying.' }

$used = if ($DerivedUsedBytes -ge 0) { $DerivedUsedBytes } else { Get-TreeBytes $derivedRoot }
if ($estimatedBytes -gt $derivedQuotaBytes - $used) { throw 'The 25 GB derived-data quota cannot reserve the MoNuSAC cohort.' }
New-Item -ItemType Directory -Path (Split-Path -Parent $reservationPath) -Force | Out-Null
[IO.File]::WriteAllText("$reservationPath.partial", [string]$estimatedBytes, [Text.UTF8Encoding]::new($false))
Move-Item -LiteralPath "$reservationPath.partial" -Destination $reservationPath -Force

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$trainingStream = [IO.File]::OpenRead($trainingArchive)
$testingStream = [IO.File]::OpenRead($testingArchive)
$trainingZip = [IO.Compression.ZipArchive]::new($trainingStream, [IO.Compression.ZipArchiveMode]::Read, $false)
$testingZip = [IO.Compression.ZipArchive]::new($testingStream, [IO.Compression.ZipArchiveMode]::Read, $false)
$samples = [Collections.Generic.List[object]]::new()
try {
    $trainingPatients = @($trainingZip.Entries | ForEach-Object { Patient-FromEntry $_.FullName } |
        Where-Object { $_ } | Sort-Object -Unique)
    $testingPatients = @($testingZip.Entries | ForEach-Object { Patient-FromEntry $_.FullName } |
        Where-Object { $_ } | Sort-Object -Unique)
    foreach ($overlap in $excludedTrainingOverlap) {
        if ($overlap -notin $trainingPatients -or $overlap -notin $testingPatients) {
            throw "Frozen overlap audit changed: $overlap"
        }
    }
    $selectedPatients = @($organPatients.Values | ForEach-Object { $_ })
    if (@($selectedPatients | Sort-Object -Unique).Count -ne 23 -or
            @($selectedPatients | Where-Object { $_ -in $trainingPatients }).Count -ne 0) {
        throw 'The selected MoNuSAC patients are not held out from training.'
    }

    $vips = Find-VipsBinary 'vips.exe'
    $vipsHeader = Find-VipsBinary 'vipsheader.exe'
    New-Item -ItemType Directory -Path (Join-Path $partialRoot 'samples') -Force | Out-Null
    foreach ($organ in $organPatients.Keys) {
        foreach ($patient in $organPatients[$organ]) {
            $patientEntries = @($testingZip.Entries | Where-Object { (Patient-FromEntry $_.FullName) -eq $patient })
            $sourceEntry = @($patientEntries | Where-Object Name -Match '\.tif$' | Sort-Object FullName | Select-Object -First 1)
            if ($sourceEntry.Count -eq 0) {
                $sourceEntry = @($patientEntries | Where-Object Name -Match '\.svs$' | Sort-Object FullName | Select-Object -First 1)
            }
            if ($sourceEntry.Count -ne 1) { throw "No bounded image entry is available for $patient" }
            $stem = [IO.Path]::GetFileNameWithoutExtension($sourceEntry[0].Name)
            $xmlEntry = @($patientEntries | Where-Object { $_.Name -eq "$stem.xml" })
            if ($xmlEntry.Count -ne 1) { throw "The paired annotation is unavailable for $patient" }

            $sampleId = $stem.ToLowerInvariant()
            $sampleRoot = Join-Path $partialRoot "samples\$sampleId"
            New-Item -ItemType Directory -Path $sampleRoot -Force | Out-Null
            $rawPath = Join-Path $sampleRoot ("source" + [IO.Path]::GetExtension($sourceEntry[0].Name).ToLowerInvariant())
            $annotationPath = Join-Path $sampleRoot 'annotation.xml'
            Copy-ZipEntry $sourceEntry[0] $rawPath
            Copy-ZipEntry $xmlEntry[0] $annotationPath
            $imagePath = Join-Path $sampleRoot 'image.png'
            Invoke-Vips $vips @('copy',$rawPath,"$imagePath.partial.png")
            Move-Item -LiteralPath "$imagePath.partial.png" -Destination $imagePath
            Remove-Item -LiteralPath $rawPath -Force
            $width = [int]((& $vipsHeader -f width $imagePath).Trim())
            $height = [int]((& $vipsHeader -f height $imagePath).Trim())
            if ($LASTEXITCODE -ne 0 -or $width -lt 64 -or $height -lt 64 -or
                    [long]$width * $height -gt 4194304L) { throw "Unsupported MoNuSAC geometry for $patient" }
            $relativeRoot = "samples/$sampleId"
            $samples.Add([ordered]@{
                sampleId=$sampleId;patientGroup=$patient;slideGroup=$sourceEntry[0].FullName.Split('/')[1]
                organ=$organ;split='qualification-held-out-test';patientOverlapWithTraining=$false
                imagePath="$relativeRoot/image.png";imageSha256=Get-Sha256 $imagePath
                annotationPath="$relativeRoot/annotation.xml";annotationSha256=Get-Sha256 $annotationPath
                width=$width;height=$height;license='CC-BY-NC-SA-4.0'
                permittedUse='private-research-restricted';sourceEntry=$sourceEntry[0].FullName
            })
        }
    }
    if (@($samples).Count -ne 23 -or @($samples.organ | Sort-Object -Unique).Count -ne 4) {
        throw 'The MoNuSAC held-out cohort is incomplete.'
    }
    Write-JsonAtomic (Join-Path $partialRoot 'cohort.json') ([ordered]@{
        schema='pathlab.cell-qualification-cohort/1';cohortId=$CohortId
        createdAt='2026-08-24T00:00:00Z';sampleCount=23;organs=@('lung','kidney','breast','prostate')
        source=[ordered]@{trainingArchiveSha256=$expectedTrainingSha;testingArchiveSha256=$expectedTestingSha
            supplementarySha256=$expectedSupplementSha;upstreamChecksumAvailable=$false
            integrity='official-exact-size-local-sha256-and-complete-selected-entry-read'}
        rights=[ordered]@{license='CC-BY-NC-SA-4.0';permittedUse='private-research-restricted';atlasCleanEligible=$false}
        splitPolicy=[ordered]@{patientHeldOutFromTraining=$true;excludedOverlapPatients=$excludedTrainingOverlap
            selection='lexicographically-first-annotated-field-per-held-out-patient'}
        gates=[ordered]@{minimumMacroPq=0.45;minimumInstanceDice=0.70;maximumCountError=0.15
            maximumMorphometryBias=0.10;maximumFailedRegionRate=0.05;requiresDeterministicRepeat=$true
            requiresCrossTissuePerformance=$true}
        samples=$samples
    })
    $actualBytes = Get-TreeBytes $partialRoot
    if ($actualBytes -gt $estimatedBytes) { throw 'The MoNuSAC cohort exceeded its derived-data reservation.' }
    Move-Item -LiteralPath $partialRoot -Destination $outputRoot
    Remove-Item -LiteralPath $reservationPath -Force
    $cohortPath = Join-Path $outputRoot 'cohort.json'
    Write-Output "Cohort: $cohortPath"
    Write-Output "SHA256: $(Get-Sha256 $cohortPath)"
    Write-Output "Samples: 23 patient-held-out fields across four organs"
} catch {
    throw
} finally {
    $testingZip.Dispose(); $testingStream.Dispose(); $trainingZip.Dispose(); $trainingStream.Dispose()
}
