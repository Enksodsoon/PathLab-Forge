[CmdletBinding()]
param(
    [ValidateSet('Start','Run','Status')] [string] $Action = 'Start',
    [string] $StateRoot = 'D:\PathLabData\EvidenceMentor\state'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$recordId = '1214456'
$datasetId = 'nct-crc-he-1214456'
$jobName = 'PathLab-NCT-CRC-HE-1214456'
$taskName = 'PathLabNctCrcAcquisition'
$sourceLimit = 45GB
$expected = [ordered]@{
    'NCT-CRC-HE-100K.zip' = @{ bytes = 11690284003L; md5 = '6fd702d11df6292bc054397ae038a464' }
    'CRC-VAL-HE-7K.zip' = @{ bytes = 800276929L; md5 = '2fd1651b4f94ebd818ebf90ad2b6ce06' }
}
$requiredBytes = 0L
foreach ($item in $expected.Values) { $requiredBytes += [long]$item.bytes }
$state = [IO.Path]::GetFullPath($StateRoot)
$acquisitionRoot = Join-Path $state "acquisition\$datasetId"
$downloadRoot = Join-Path $acquisitionRoot 'downloads'
$sourceRoot = Join-Path $state "sources\$datasetId"
$statusPath = Join-Path $acquisitionRoot 'status.json'
$recordPath = Join-Path $acquisitionRoot 'zenodo-record.json'
$ledgerPath = Join-Path $sourceRoot 'sample-ledger.jsonl'
$reservationPath = Join-Path $state "quota\reservations\source\$datasetId.reservation"

function Write-JsonAtomic([string] $Path, [object] $Value) {
    $parent = Split-Path -Parent $Path
    New-Item -ItemType Directory -Path $parent -Force | Out-Null
    $partial = "$Path.partial"
    [IO.File]::WriteAllText($partial, (($Value | ConvertTo-Json -Depth 16) + "`n"),
        [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $partial -Destination $Path -Force
}

function Write-Status([string] $State, [long] $Completed, [long] $Total, [string] $Detail) {
    Write-JsonAtomic $statusPath ([ordered]@{
        schema = 'pathlab.acquisition-status/1'; datasetId = $datasetId; state = $State
        completedBytes = $Completed; totalBytes = $Total; detail = $Detail
        networkContext = 'interactive-user-acquisition-only'; analysisNetwork = 'disabled'
        updatedAt = [DateTimeOffset]::UtcNow.ToString('o')
    })
}

function Directory-Bytes([string] $Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Container)) { return 0L }
    $measure = Get-ChildItem -LiteralPath $Path -File -Recurse -ErrorAction Stop |
        Measure-Object -Property Length -Sum
    if ($null -eq $measure -or $null -eq $measure.Sum) { return 0L }
    return [long]$measure.Sum
}

function Remove-Finalizer {
    Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue
}

function Get-DownloadedBytes {
    $completed = 0L
    foreach ($name in $expected.Keys) {
        foreach ($candidate in @((Join-Path $downloadRoot $name), (Join-Path $downloadRoot "$name.partial"))) {
            if (Test-Path -LiteralPath $candidate -PathType Leaf) {
                $completed += [Math]::Min([long](Get-Item -LiteralPath $candidate).Length,
                    [long]$expected[$name].bytes)
                break
            }
        }
    }
    return $completed
}

function Move-ToQuarantine([string] $Path, [string] $Reason) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return }
    $resolved = [IO.Path]::GetFullPath($Path)
    $resolvedDownloadRoot = [IO.Path]::GetFullPath($downloadRoot)
    if (-not $resolved.StartsWith($resolvedDownloadRoot + [IO.Path]::DirectorySeparatorChar,
            [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Refusing to quarantine a file outside the acquisition download root.'
    }
    $quarantineRoot = Join-Path $downloadRoot 'quarantine'
    New-Item -ItemType Directory -Path $quarantineRoot -Force | Out-Null
    $stamp = [DateTimeOffset]::UtcNow.ToString('yyyyMMddTHHmmssfffZ')
    $target = Join-Path $quarantineRoot "$([IO.Path]::GetFileName($Path)).corrupt-$stamp"
    Move-Item -LiteralPath $resolved -Destination $target
    Write-JsonAtomic "$target.json" ([ordered]@{
        schema = 'pathlab.acquisition-quarantine/1'
        datasetId = $datasetId
        fileName = [IO.Path]::GetFileName($Path)
        bytes = [long](Get-Item -LiteralPath $target).Length
        reason = $Reason
        quarantinedAt = [DateTimeOffset]::UtcNow.ToString('o')
    })
}

if ($Action -eq 'Status') {
    if (Test-Path -LiteralPath $statusPath -PathType Leaf) { Get-Content -LiteralPath $statusPath -Raw }
    else { '{"schema":"pathlab.acquisition-status/1","state":"not_started"}' }
    return
}

foreach ($directory in @($acquisitionRoot, $downloadRoot, (Split-Path -Parent $reservationPath))) {
    if (-not (Test-Path -LiteralPath $directory -PathType Container)) {
        New-Item -ItemType Directory -Path $directory -Force | Out-Null
    }
}

if ($Action -eq 'Start') {
    if (Test-Path -LiteralPath $statusPath -PathType Leaf) {
        $prior = Get-Content -LiteralPath $statusPath -Raw | ConvertFrom-Json
        if ($prior.state -eq 'completed') { $prior | ConvertTo-Json -Depth 8; return }
    }
    $used = Directory-Bytes (Join-Path $state 'sources')
    $reserved = 0L
    Get-ChildItem -LiteralPath (Split-Path -Parent $reservationPath) -Filter '*.reservation' -File `
        -ErrorAction SilentlyContinue | Where-Object FullName -ne $reservationPath | ForEach-Object {
            $reserved += [long]([IO.File]::ReadAllText($_.FullName).Trim())
        }
    if ($requiredBytes -gt $sourceLimit - $used - $reserved) { throw 'The 45 GB source quota cannot reserve NCT-CRC.' }
    if (-not (Test-Path -LiteralPath $reservationPath -PathType Leaf)) {
        [IO.File]::WriteAllText("$reservationPath.partial", [string]$requiredBytes,
            [Text.UTF8Encoding]::new($false))
        Move-Item -LiteralPath "$reservationPath.partial" -Destination $reservationPath
    }

    $record = Invoke-RestMethod -Uri "https://zenodo.org/api/records/$recordId" -TimeoutSec 60
    if ($record.id -ne 1214456 -or $record.metadata.doi -ne '10.5281/zenodo.1214456' -or
            $record.metadata.license.id -ne 'cc-by-4.0') {
        throw 'Zenodo identity or license did not match the frozen NCT-CRC acquisition policy.'
    }
    foreach ($name in $expected.Keys) {
        $file = @($record.files | Where-Object key -eq $name)
        if ($file.Count -ne 1 -or [long]$file[0].size -ne [long]$expected[$name].bytes -or
                $file[0].checksum -ne "md5:$($expected[$name].md5)") {
            throw "Zenodo file metadata changed: $name"
        }
    }
    Write-JsonAtomic $recordPath $record
    Get-BitsTransfer -Name $jobName -ErrorAction SilentlyContinue |
        Remove-BitsTransfer -Confirm:$false -ErrorAction SilentlyContinue
    $durableScript = Join-Path $acquisitionRoot 'acquire-nct-crc-he.ps1'
    Copy-Item -LiteralPath $PSCommandPath -Destination $durableScript -Force
    $taskAction = New-ScheduledTaskAction -Execute 'powershell.exe' `
        -Argument "-NoProfile -ExecutionPolicy Bypass -File `"$durableScript`" -Action Run -StateRoot `"$state`""
    $trigger = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) `
        -RepetitionInterval (New-TimeSpan -Minutes 5) -RepetitionDuration (New-TimeSpan -Days 7)
    $principal = New-ScheduledTaskPrincipal -UserId ([Security.Principal.WindowsIdentity]::GetCurrent().Name) `
        -LogonType Interactive -RunLevel Limited
    $settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -MultipleInstances IgnoreNew `
        -ExecutionTimeLimit (New-TimeSpan -Days 7)
    Register-ScheduledTask -TaskName $taskName -Action $taskAction -Trigger $trigger -Principal $principal `
        -Settings $settings -Description 'Run checksum-bound NCT-CRC acquisition for PathLab.' -Force | Out-Null
    Write-Status 'transferring' 0 $requiredBytes 'Clean non-resumable acquisition worker scheduled.'
    Get-Content -LiteralPath $statusPath -Raw
    return
}

if (Test-Path -LiteralPath $statusPath) {
    $prior = Get-Content -LiteralPath $statusPath -Raw | ConvertFrom-Json
    if ($prior.state -eq 'completed') { Remove-Finalizer; return }
    if ($prior.state -eq 'failed') { Remove-Finalizer; return }
}

$curl = (Get-Command curl.exe -ErrorAction Stop).Source
foreach ($name in $expected.Keys) {
    $download = Join-Path $downloadRoot $name
    if (Test-Path -LiteralPath $download -PathType Leaf) {
        if ((Get-Item -LiteralPath $download).Length -eq [long]$expected[$name].bytes) { continue }
        throw "Existing acquisition file has the wrong size: $name"
    }
    $partial = "$download.partial"
    if (Test-Path -LiteralPath $partial -PathType Leaf) {
        $partialLength = [long](Get-Item -LiteralPath $partial).Length
        if ($partialLength -eq [long]$expected[$name].bytes -and
                (Get-FileHash -LiteralPath $partial -Algorithm MD5).Hash.ToLowerInvariant() -eq
                    $expected[$name].md5) {
            Move-Item -LiteralPath $partial -Destination $download
            continue
        }
        Move-ToQuarantine $partial 'Interrupted, oversized, or checksum-invalid non-resumable transfer.'
    }
    $url = "https://zenodo.org/api/records/$recordId/files/$([Uri]::EscapeDataString($name))/content"
    $exitCode = -1
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        if (Test-Path -LiteralPath $partial -PathType Leaf) {
            Remove-Item -LiteralPath $partial -Force
        }
        Write-Status 'transferring' (Get-DownloadedBytes) $requiredBytes "Downloading $name (attempt $attempt of 3)"
        $arguments = @('--fail','--location','--silent','--show-error','--remove-on-error',
            '--output',('"' + $partial + '"'),$url)
        $process = Start-Process -FilePath $curl -ArgumentList $arguments -PassThru -NoNewWindow
        while (-not $process.HasExited) {
            Write-Status 'transferring' (Get-DownloadedBytes) $requiredBytes "Downloading $name (attempt $attempt of 3)"
            Start-Sleep -Seconds 15
            $process.Refresh()
        }
        $process.WaitForExit()
        $exitCode = [int]$process.ExitCode
        if ($exitCode -eq 0) { break }
        if (Test-Path -LiteralPath $partial -PathType Leaf) {
            Remove-Item -LiteralPath $partial -Force
        }
        if ($attempt -lt 3) {
            $delay = if ($attempt -eq 1) { 5 } else { 30 }
            Write-Status 'transient_error' (Get-DownloadedBytes) $requiredBytes `
                "Download attempt $attempt failed with curl exit $exitCode; retrying in $delay seconds."
            Start-Sleep -Seconds $delay
        }
    }
    if ($exitCode -ne 0) {
        Write-Status 'failed' (Get-DownloadedBytes) $requiredBytes `
            "Download stopped after 3 attempts; curl exit $exitCode."
        throw "NCT-CRC download failed after three attempts with curl exit code $exitCode."
    }
    if ((Get-Item -LiteralPath $partial).Length -ne [long]$expected[$name].bytes) {
        Write-Status 'failed' (Get-DownloadedBytes) $requiredBytes "Downloaded size mismatch: $name"
        throw "NCT-CRC downloaded size mismatch: $name"
    }
    Move-Item -LiteralPath $partial -Destination $download
}

Write-Status 'validating' $requiredBytes $requiredBytes 'Validating official MD5 and local SHA-256 checksums.'
New-Item -ItemType Directory -Path $sourceRoot -Force | Out-Null
$ledger = [Collections.Generic.List[string]]::new()
foreach ($name in $expected.Keys) {
    $download = Join-Path $downloadRoot $name
    if ((Get-Item -LiteralPath $download).Length -ne [long]$expected[$name].bytes -or
            (Get-FileHash -LiteralPath $download -Algorithm MD5).Hash.ToLowerInvariant() -ne $expected[$name].md5) {
        Write-Status 'failed' 0 $requiredBytes "Checksum validation failed: $name"
        throw "NCT-CRC checksum validation failed: $name"
    }
    $target = Join-Path $sourceRoot $name
    Move-Item -LiteralPath $download -Destination $target -Force
    $ledger.Add(([ordered]@{
        sampleId = [IO.Path]::GetFileNameWithoutExtension($name); source = 'Zenodo record 1214456'
        patientGroup = 'dataset-defined-multiple-patients'; slideGroup = 'dataset-defined-patches'
        sha256 = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()
        upstreamMd5 = $expected[$name].md5; bytes = [long]$expected[$name].bytes
        license = 'CC-BY-4.0'; permittedUse = 'private-research'; task = 'he-retrieval-qualification'
        split = if ($name -like 'CRC-VAL*') { 'query' } else { 'reference' }
    } | ConvertTo-Json -Compress))
}
[IO.File]::WriteAllLines($ledgerPath, $ledger, [Text.UTF8Encoding]::new($false))
Copy-Item -LiteralPath $recordPath -Destination (Join-Path $sourceRoot 'zenodo-record.json') -Force
Remove-Item -LiteralPath $reservationPath -Force
Write-Status 'completed' $requiredBytes $requiredBytes 'NCT-CRC source archives are checksum-verified and ledgered.'
Remove-Finalizer
