[CmdletBinding()]
param(
    [ValidateSet('Start','Run','Status')] [string] $Action = 'Start',
    [string] $StateRoot = 'D:\PathLabData\EvidenceMentor\state'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$datasetId = 'monusac2020-official-v1'
$taskName = 'PathLabMonusacAcquisition'
$sourceLimit = 45GB
$sourcePage = 'https://monusac-2020.grand-challenge.org/Data/'
$expected = @(
    [ordered]@{kind='training';driveId='1lxMZaAPSpEHLSxGA9KKMt_r-4S8dwLhq';name='MoNuSAC_images_and_annotations.zip';bytes=545564883L},
    [ordered]@{kind='testing';driveId='1G54vsOdxWY1hG7dzmkeK3r0xz9s-heyQ';name='MoNuSAC Testing Data and Annotations.zip';bytes=202746703L},
    [ordered]@{kind='supplement';driveId='1kdOl3s6uQBRv0nToSIf1dPuceZunzL4N';name='MoNuSAC_Supplementary_material.pdf';bytes=19590377L}
)
$requiredBytes = 0L
foreach ($item in $expected) { $requiredBytes += [long]$item.bytes }
$state = [IO.Path]::GetFullPath($StateRoot)
$root = Join-Path $state "acquisition\$datasetId"
$downloadRoot = Join-Path $root 'downloads'
$sourceRoot = Join-Path $state "sources\$datasetId"
$statusPath = Join-Path $root 'status.json'
$sourcePagePath = Join-Path $root 'official-data-page.html'
$rightsPath = Join-Path $root 'rights-and-integrity.json'
$ledgerPath = Join-Path $sourceRoot 'sample-ledger.jsonl'
$integrityPath = Join-Path $sourceRoot 'local-integrity.json'
$reservationPath = Join-Path $state "quota\reservations\source\$datasetId.reservation"

function Write-JsonAtomic([string] $Path, [object] $Value) {
    New-Item -ItemType Directory -Path (Split-Path -Parent $Path) -Force | Out-Null
    [IO.File]::WriteAllText("$Path.partial", (($Value | ConvertTo-Json -Depth 20) + "`n"),
        [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath "$Path.partial" -Destination $Path -Force
}

function Write-Status([string] $StateValue, [long] $Completed, [string] $Detail) {
    Write-JsonAtomic $statusPath ([ordered]@{
        schema='pathlab.acquisition-status/1';datasetId=$datasetId;state=$StateValue
        completedBytes=$Completed;totalBytes=$requiredBytes;detail=$Detail
        networkContext='interactive-user-acquisition-only';analysisNetwork='disabled'
        updatedAt=[DateTimeOffset]::UtcNow.ToString('o')
    })
}

function Directory-Bytes([string] $Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Container)) { return 0L }
    $sum = Get-ChildItem -LiteralPath $Path -File -Recurse | Measure-Object Length -Sum
    if ($null -eq $sum -or $null -eq $sum.Sum) { return 0L }
    return [long]$sum.Sum
}

function Get-DownloadedBytes {
    $total = 0L
    foreach ($item in $expected) {
        $published = Join-Path $sourceRoot $item.name
        if (Test-Path -LiteralPath $published -PathType Leaf) {
            $total += [Math]::Min([long](Get-Item -LiteralPath $published).Length, [long]$item.bytes)
            continue
        }
        $target = Join-Path $downloadRoot $item.name
        if (Test-Path -LiteralPath $target -PathType Leaf) {
            $total += [Math]::Min([long](Get-Item -LiteralPath $target).Length, [long]$item.bytes)
            continue
        }
        $chunkRoot = Join-Path $downloadRoot ".chunks\$($item.kind)"
        $total += [Math]::Min((Directory-Bytes $chunkRoot), [long]$item.bytes)
    }
    return $total
}

function Finish-Task {
    Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue
}

if ($Action -eq 'Status') {
    if (Test-Path -LiteralPath $statusPath -PathType Leaf) { Get-Content $statusPath -Raw }
    else { '{"schema":"pathlab.acquisition-status/1","state":"not_started"}' }
    return
}

New-Item -ItemType Directory -Path $downloadRoot -Force | Out-Null
$reservationRoot = Split-Path -Parent $reservationPath
if (-not (Test-Path -LiteralPath $reservationRoot -PathType Container)) {
    throw 'The service-managed source quota reservation root is unavailable.'
}

if ($Action -eq 'Start') {
    if (Test-Path -LiteralPath $statusPath -PathType Leaf) {
        $prior = Get-Content $statusPath -Raw | ConvertFrom-Json
        if ($prior.state -eq 'completed') { Get-Content $statusPath -Raw; return }
    }

    $used = Directory-Bytes (Join-Path $state 'sources')
    $reserved = 0L
    Get-ChildItem $reservationRoot -Filter '*.reservation' -File -ErrorAction SilentlyContinue |
        Where-Object FullName -ne $reservationPath |
        ForEach-Object { $reserved += [long]([IO.File]::ReadAllText($_.FullName).Trim()) }
    if ($requiredBytes -gt $sourceLimit - $used - $reserved) {
        throw 'The 45 GB source quota cannot reserve the official MoNuSAC artifacts.'
    }
    if (-not (Test-Path -LiteralPath $reservationPath)) {
        [IO.File]::WriteAllText("$reservationPath.partial", [string]$requiredBytes)
        Move-Item -LiteralPath "$reservationPath.partial" -Destination $reservationPath
    }

    $page = Invoke-WebRequest -Uri $sourcePage -UseBasicParsing -TimeoutSec 60 -Headers @{'User-Agent'='PathLab-Forge/2.1 research acquisition'}
    [IO.File]::WriteAllText("$sourcePagePath.partial", [string]$page.Content, [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath "$sourcePagePath.partial" -Destination $sourcePagePath -Force
    $pageText = [string]$page.Content
    if ($pageText -notmatch 'CC BY-NC-SA 4.0') { throw 'The official MoNuSAC license statement is unavailable or changed.' }
    foreach ($item in $expected) {
        if (-not $pageText.Contains([string]$item.driveId)) { throw "The official MoNuSAC link changed: $($item.kind)" }
    }
    $pageSha = (Get-FileHash $sourcePagePath -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-JsonAtomic $rightsPath ([ordered]@{
        schema='pathlab.source-rights-review/1';datasetId=$datasetId;source=$sourcePage
        sourcePageSha256=$pageSha;license='CC-BY-NC-SA-4.0'
        permittedUse='private-research-restricted';upstreamChecksumAvailable=$false
        integrityPolicy='source-integrity-frozen-after-first-acquisition'
        atlasResearchEligible=$true;atlasCleanEligible=$false
        qualificationStatus='not_evaluable';reason='OFFICIAL_UPSTREAM_CHECKSUM_NOT_PUBLISHED'
        reviewedAt=[DateTimeOffset]::UtcNow.ToString('o')
    })

    $durable = Join-Path $root 'acquire-monusac2020.ps1'
    Copy-Item -LiteralPath $PSCommandPath -Destination $durable -Force
    $taskAction = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument "-NoProfile -ExecutionPolicy Bypass -File `"$durable`" -Action Run -StateRoot `"$state`""
    $trigger = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1)
    $principal = New-ScheduledTaskPrincipal -UserId ([Security.Principal.WindowsIdentity]::GetCurrent().Name) -LogonType Interactive -RunLevel Limited
    $settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Days 7)
    Register-ScheduledTask -TaskName $taskName -Action $taskAction -Trigger $trigger -Principal $principal -Settings $settings -Description 'Acquire official restricted MoNuSAC cell-instance evaluation artifacts.' -Force | Out-Null
    Write-Status 'queued' (Get-DownloadedBytes) 'Official links and restricted-use rights verified; acquisition scheduled.'
    Start-ScheduledTask $taskName
    Get-Content $statusPath -Raw
    return
}

$curl = (Get-Command curl.exe -ErrorAction Stop).Source
$chunkBytes = 8MB
$integrity = [Collections.Generic.List[object]]::new()
try {
    foreach ($item in $expected) {
        $published = Join-Path $sourceRoot $item.name
        if (Test-Path -LiteralPath $published -PathType Leaf) {
            if ((Get-Item -LiteralPath $published).Length -ne [long]$item.bytes) { throw "Published MoNuSAC artifact size changed: $($item.name)" }
            $publishedSha = (Get-FileHash $published -Algorithm SHA256).Hash.ToLowerInvariant()
            $integrity.Add([ordered]@{kind=$item.kind;name=$item.name;driveId=$item.driveId;bytes=$item.bytes;sha256=$publishedSha})
            continue
        }

        $target = Join-Path $downloadRoot $item.name
        $partial = "$target.partial"
        Remove-Item -LiteralPath $partial -Force -ErrorAction SilentlyContinue
        $chunkRoot = Join-Path $downloadRoot ".chunks\$($item.kind)"
        New-Item -ItemType Directory -Path $chunkRoot -Force | Out-Null
        $url = "https://drive.usercontent.google.com/download?id=$($item.driveId)&export=download&confirm=t"
        $chunkIndex = 0
        for ($offset = 0L; $offset -lt [long]$item.bytes; $offset += $chunkBytes) {
            $end = [Math]::Min([long]$item.bytes - 1, $offset + $chunkBytes - 1)
            $expectedChunkBytes = $end - $offset + 1
            $chunk = Join-Path $chunkRoot ('{0:D5}.part' -f $chunkIndex)
            if (Test-Path -LiteralPath $chunk) {
                if ((Get-Item -LiteralPath $chunk).Length -eq $expectedChunkBytes) { $chunkIndex++; continue }
                Remove-Item -LiteralPath $chunk -Force
            }
            if ((Test-Path -LiteralPath "$chunk.partial") -and (Get-Item -LiteralPath "$chunk.partial").Length -eq $expectedChunkBytes) {
                Move-Item -LiteralPath "$chunk.partial" -Destination $chunk -Force
                $chunkIndex++
                continue
            }
            for ($attempt = 1; $attempt -le 3; $attempt++) {
                Write-Status 'transferring' (Get-DownloadedBytes) "Downloading $($item.kind) chunk $($chunkIndex + 1) (attempt $attempt of 3)."
                $headerPath = "$chunk.headers.partial"
                Remove-Item -LiteralPath $headerPath -Force -ErrorAction SilentlyContinue
                $process = Start-Process $curl -ArgumentList @('--fail','--location','--silent','--show-error','--remove-on-error','--range',"$offset-$end",'--dump-header',('"'+$headerPath+'"'),'--output',('"'+$chunk+'.partial"'),$url) -PassThru -NoNewWindow
                while (-not $process.HasExited) {
                    Write-Status 'transferring' (Get-DownloadedBytes) "Downloading $($item.kind) chunk $($chunkIndex + 1) (attempt $attempt of 3)."
                    Start-Sleep -Seconds 15
                    $process.Refresh()
                }
                $process.WaitForExit()
                $expectedContentRange = "bytes $offset-$end/$($item.bytes)"
                $hasExpectedContentRange = (Test-Path -LiteralPath $headerPath -PathType Leaf) -and
                    ((Get-Content -LiteralPath $headerPath -Raw) -match
                        ('(?im)^Content-Range:\s*' + [Regex]::Escape($expectedContentRange) + '\s*$'))
                if ($hasExpectedContentRange -and (Test-Path -LiteralPath "$chunk.partial") -and
                        (Get-Item -LiteralPath "$chunk.partial").Length -eq $expectedChunkBytes) {
                    Move-Item -LiteralPath "$chunk.partial" -Destination $chunk -Force
                    Remove-Item -LiteralPath $headerPath -Force -ErrorAction SilentlyContinue
                    break
                }
                Remove-Item -LiteralPath "$chunk.partial" -Force -ErrorAction SilentlyContinue
                Remove-Item -LiteralPath $headerPath -Force -ErrorAction SilentlyContinue
                if ($attempt -lt 3) { Start-Sleep -Seconds $(if ($attempt -eq 1) { 5 } else { 30 }) }
            }
            if (-not (Test-Path -LiteralPath $chunk) -or (Get-Item -LiteralPath $chunk).Length -ne $expectedChunkBytes) {
                throw "MoNuSAC chunk transfer failed: $($item.name) bytes $offset-$end"
            }
            $chunkIndex++
        }

        $output = [IO.File]::Open($partial, [IO.FileMode]::Create, [IO.FileAccess]::Write, [IO.FileShare]::None)
        try {
            Get-ChildItem -LiteralPath $chunkRoot -Filter '*.part' -File | Sort-Object Name | ForEach-Object {
                $input = [IO.File]::OpenRead($_.FullName)
                try { $input.CopyTo($output) } finally { $input.Dispose() }
            }
        } finally { $output.Dispose() }
        if ((Get-Item -LiteralPath $partial).Length -ne [long]$item.bytes) { throw "MoNuSAC assembled size mismatch: $($item.name)" }
        $sha256 = (Get-FileHash $partial -Algorithm SHA256).Hash.ToLowerInvariant()
        Move-Item -LiteralPath $partial -Destination $target -Force
        Remove-Item -LiteralPath $chunkRoot -Recurse -Force
        $integrity.Add([ordered]@{kind=$item.kind;name=$item.name;driveId=$item.driveId;bytes=$item.bytes;sha256=$sha256})
    }
} catch {
    Write-Status 'failed' (Get-DownloadedBytes) $_.Exception.Message
    Finish-Task
    throw
}

Write-Status 'validating' $requiredBytes 'Publishing locally frozen SHA-256 values and restricted source ledger.'
New-Item -ItemType Directory -Path $sourceRoot -Force | Out-Null
$lines = [Collections.Generic.List[string]]::new()
foreach ($item in $expected) {
    $record = @($integrity | Where-Object name -eq $item.name)
    if ($record.Count -ne 1) { throw "Local MoNuSAC integrity record missing: $($item.name)" }
    $from = Join-Path $downloadRoot $item.name
    $to = Join-Path $sourceRoot $item.name
    if (Test-Path -LiteralPath $from -PathType Leaf) { Move-Item -LiteralPath $from -Destination $to -Force }
    $lines.Add(([ordered]@{
        sampleId="monusac-$($item.kind)-archive";source=$sourcePage;patientGroup='archive-metadata-pending'
        slideGroup='archive-metadata-pending';driveId=$item.driveId;fileName=$item.name
        sha256=$record[0].sha256;bytes=$item.bytes;upstreamChecksumAvailable=$false
        license='CC-BY-NC-SA-4.0';permittedUse='private-research-restricted'
        task='cell-instance-qualification-preparation';split='unassigned';atlasCleanEligible=$false
    } | ConvertTo-Json -Compress))
}
[IO.File]::WriteAllLines($ledgerPath, $lines, [Text.UTF8Encoding]::new($false))
Write-JsonAtomic $integrityPath ([ordered]@{
    schema='pathlab.local-source-integrity/1';datasetId=$datasetId
    integrityPolicy='source-integrity-frozen-after-first-acquisition'
    upstreamChecksumAvailable=$false;artifacts=$integrity
    frozenAt=[DateTimeOffset]::UtcNow.ToString('o')
})
Copy-Item -LiteralPath $sourcePagePath -Destination (Join-Path $sourceRoot 'official-data-page.html') -Force
Copy-Item -LiteralPath $rightsPath -Destination (Join-Path $sourceRoot 'rights-and-integrity.json') -Force
Remove-Item -LiteralPath $reservationPath -Force
Write-Status 'completed' $requiredBytes 'Official archives acquired and locally SHA-256 frozen; cell qualification remains not_evaluable pending metadata extraction and upstream-integrity corroboration.'
Finish-Task
