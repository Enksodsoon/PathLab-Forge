[CmdletBinding()]
param(
    [ValidateSet('Start','Run','Status')] [string] $Action = 'Start',
    [string] $StateRoot = 'D:\PathLabData\EvidenceMentor\state'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$candidateId = 'hovernet-fast-monusac-v1'
$candidateVersion = '1'
$taskName = 'PathLabHoverNetAcquisition'
$modelLimit = 10GB
$reservationBytes = 512MB
$repository = 'https://github.com/vqdang/hover_net.git'
$commit = '67e2ce5e3f1a64a2ece77ad1c24233653a9e0901'
$readmeUrl = "https://raw.githubusercontent.com/vqdang/hover_net/$commit/README.md"
$licenseUrl = "https://raw.githubusercontent.com/vqdang/hover_net/$commit/LICENSE"
$codeArchiveUrl = "https://codeload.github.com/vqdang/hover_net/zip/$commit"
$weightDriveId = '13qkxDqv7CUqxN-l5CpeFVmc24mDw6CeV'
$weightName = 'hovernet_fast_monusac_type_tf2pytorch.tar'
$weightBytes = 150995854L
$chunkBytes = 8MB
$state = [IO.Path]::GetFullPath($StateRoot)
$modelsRoot = [IO.Path]::GetFullPath((Join-Path $state 'models'))
$candidateRoot = [IO.Path]::GetFullPath((Join-Path $modelsRoot "$candidateId\$candidateVersion"))
$stagingRoot = [IO.Path]::GetFullPath((Join-Path $modelsRoot ".partial-$candidateId-$candidateVersion"))
$acquisitionRoot = Join-Path $state "acquisition\$candidateId"
$statusPath = Join-Path $acquisitionRoot 'status.json'
$reservationPath = Join-Path $modelsRoot ".acquisition-reservations\$candidateId.reservation"
$runtimeManifest = Join-Path $modelsRoot 'he-dinov2-small-v1\1\runtime-manifest.json'

if (-not $candidateRoot.StartsWith($modelsRoot + [IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase) -or
        -not $stagingRoot.StartsWith($modelsRoot + [IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase)) {
    throw 'HoVer-Net candidate path escaped the Evidence Mentor model root.'
}

function Write-JsonAtomic([string] $Path, [object] $Value) {
    New-Item -ItemType Directory -Path (Split-Path -Parent $Path) -Force | Out-Null
    [IO.File]::WriteAllText("$Path.partial", (($Value | ConvertTo-Json -Depth 20) + "`n"),
        [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath "$Path.partial" -Destination $Path -Force
}

function Write-Status([string] $StateValue, [long] $Completed, [string] $Detail) {
    Write-JsonAtomic $statusPath ([ordered]@{
        schema='pathlab.acquisition-status/1';datasetId=$candidateId;state=$StateValue
        completedBytes=$Completed;totalBytes=$weightBytes;detail=$Detail
        networkContext='interactive-user-acquisition-only';analysisNetwork='disabled'
        updatedAt=[DateTimeOffset]::UtcNow.ToString('o')
    })
}

function Directory-Bytes([string] $Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Container)) { return 0L }
    $sum = 0L
    Get-ChildItem -LiteralPath $Path -File -Recurse -ErrorAction Stop | ForEach-Object {
        $sum = [long]($sum + $_.Length)
    }
    return $sum
}

function Get-DownloadedBytes {
    $published = Join-Path $candidateRoot $weightName
    if (Test-Path -LiteralPath $published -PathType Leaf) {
        return [Math]::Min([long](Get-Item -LiteralPath $published).Length, $weightBytes)
    }
    $chunkRoot = Join-Path $stagingRoot '.weight-chunks'
    return [Math]::Min((Directory-Bytes $chunkRoot), $weightBytes)
}

function Finish-Task {
    Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue
}

if ($Action -eq 'Status') {
    if (Test-Path -LiteralPath $statusPath -PathType Leaf) { Get-Content -LiteralPath $statusPath -Raw }
    else { '{"schema":"pathlab.acquisition-status/1","state":"not_started"}' }
    return
}

if (Test-Path -LiteralPath (Join-Path $candidateRoot 'candidate-ledger.json') -PathType Leaf) {
    $ledger = Get-Content -LiteralPath (Join-Path $candidateRoot 'candidate-ledger.json') -Raw | ConvertFrom-Json
    if ($ledger.schema -ne 'pathlab.model-candidate-acquisition/1' -or
            $ledger.candidateId -ne $candidateId -or $ledger.code.commit -ne $commit) {
        throw 'Existing HoVer-Net candidate ledger is incompatible.'
    }
    $existingCode = Join-Path $candidateRoot ([string]$ledger.code.archive)
    $existingWeight = Join-Path $candidateRoot ([string]$ledger.weights.fileName)
    if (-not (Test-Path -LiteralPath $existingCode -PathType Leaf) -or
            -not (Test-Path -LiteralPath $existingWeight -PathType Leaf) -or
            (Get-Item -LiteralPath $existingWeight).Length -ne $weightBytes -or
            (Get-FileHash -LiteralPath $existingCode -Algorithm SHA256).Hash.ToLowerInvariant() -ne [string]$ledger.code.sha256 -or
            (Get-FileHash -LiteralPath $existingWeight -Algorithm SHA256).Hash.ToLowerInvariant() -ne [string]$ledger.weights.sha256) {
        throw 'Existing HoVer-Net candidate artifact checksum changed.'
    }
    Write-Status 'completed' $weightBytes 'Restricted HoVer-Net candidate is already checksum-frozen; qualification remains not_evaluable.'
    return
}

New-Item -ItemType Directory -Path $acquisitionRoot -Force | Out-Null
New-Item -ItemType Directory -Path $stagingRoot -Force | Out-Null
$reservationRoot = Split-Path -Parent $reservationPath
New-Item -ItemType Directory -Path $reservationRoot -Force | Out-Null

if ($Action -eq 'Start') {
    $used = Directory-Bytes $modelsRoot
    $reserved = 0L
    Get-ChildItem -LiteralPath $reservationRoot -Filter '*.reservation' -File -ErrorAction SilentlyContinue |
        Where-Object FullName -ne $reservationPath |
        ForEach-Object { $reserved += [long]([IO.File]::ReadAllText($_.FullName).Trim()) }
    if ($reservationBytes -gt $modelLimit - $used - $reserved) {
        throw 'The 10 GB model quota cannot reserve the HoVer-Net candidate.'
    }
    if (-not (Test-Path -LiteralPath $reservationPath -PathType Leaf)) {
        [IO.File]::WriteAllText("$reservationPath.partial", [string]$reservationBytes)
        Move-Item -LiteralPath "$reservationPath.partial" -Destination $reservationPath
    }

    $headers = @{'User-Agent'='PathLab-Forge/2.1 research acquisition'}
    $readmePath = Join-Path $stagingRoot 'UPSTREAM-README.md'
    $licensePath = Join-Path $stagingRoot 'UPSTREAM-LICENSE'
    Invoke-WebRequest -Uri $readmeUrl -OutFile "$readmePath.partial" -Headers $headers -TimeoutSec 60
    Move-Item -LiteralPath "$readmePath.partial" -Destination $readmePath -Force
    Invoke-WebRequest -Uri $licenseUrl -OutFile "$licensePath.partial" -Headers $headers -TimeoutSec 60
    Move-Item -LiteralPath "$licensePath.partial" -Destination $licensePath -Force
    $readme = Get-Content -LiteralPath $readmePath -Raw
    $license = Get-Content -LiteralPath $licensePath -Raw
    if (-not $readme.Contains($weightDriveId) -or $readme -notmatch 'MoNuSAC checkpoint') {
        throw 'The pinned official HoVer-Net README no longer identifies the expected MoNuSAC fast checkpoint.'
    }
    if ($license -notmatch 'MIT License') { throw 'The pinned HoVer-Net code license changed.' }

    Write-JsonAtomic (Join-Path $stagingRoot 'rights-review.json') ([ordered]@{
        schema='pathlab.model-rights-review/1';candidateId=$candidateId
        repository=$repository;commit=$commit
        codeLicense='MIT';weightLicense='CC-BY-NC-SA-4.0'
        codeAndWeightLicensesReviewedSeparately=$true
        permittedUse='private-research-restricted';upstreamChecksumAvailable=$false
        integrityPolicy='source-integrity-frozen-after-first-acquisition'
        atlasResearchEligible=$true;atlasCleanEligible=$false
        qualificationStatus='not_evaluable'
        reason='RUNTIME_ADAPTATION_AND_HELD_OUT_EXECUTION_PENDING'
        reviewedAt=[DateTimeOffset]::UtcNow.ToString('o')
    })

    $durable = Join-Path $acquisitionRoot 'acquire-hovernet-candidate.ps1'
    Copy-Item -LiteralPath $PSCommandPath -Destination $durable -Force
    $taskAction = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument "-NoProfile -ExecutionPolicy Bypass -File `"$durable`" -Action Run -StateRoot `"$state`""
    $trigger = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1)
    $principal = New-ScheduledTaskPrincipal -UserId ([Security.Principal.WindowsIdentity]::GetCurrent().Name) -LogonType Interactive -RunLevel Limited
    $settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Days 2)
    Register-ScheduledTask -TaskName $taskName -Action $taskAction -Trigger $trigger -Principal $principal -Settings $settings -Description 'Acquire the official restricted HoVer-Net fast MoNuSAC research candidate.' -Force | Out-Null
    Write-Status 'queued' (Get-DownloadedBytes) 'Official code and weight rights verified; checksum-frozen acquisition scheduled.'
    Start-ScheduledTask $taskName
    Get-Content -LiteralPath $statusPath -Raw
    return
}

$curl = (Get-Command curl.exe -ErrorAction Stop).Source
try {
    $codeArchive = Join-Path $stagingRoot "hover_net-$commit.zip"
    if (-not (Test-Path -LiteralPath $codeArchive -PathType Leaf)) {
        Write-Status 'transferring' (Get-DownloadedBytes) 'Downloading the exact official HoVer-Net code revision.'
        Remove-Item -LiteralPath "$codeArchive.partial" -Force -ErrorAction SilentlyContinue
        & $curl '--fail' '--location' '--silent' '--show-error' '--retry' '3' '--max-time' '300' '--user-agent' 'PathLab-Forge/2.1 research acquisition' '--output' "$codeArchive.partial" $codeArchiveUrl
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath "$codeArchive.partial" -PathType Leaf) -or
                (Get-Item -LiteralPath "$codeArchive.partial").Length -eq 0) {
            throw 'The exact HoVer-Net code revision download failed.'
        }
        Move-Item -LiteralPath "$codeArchive.partial" -Destination $codeArchive -Force
    }
    $codeSha256 = (Get-FileHash -LiteralPath $codeArchive -Algorithm SHA256).Hash.ToLowerInvariant()

    $weightPartial = Join-Path $stagingRoot "$weightName.partial"
    $weightFinal = Join-Path $stagingRoot $weightName
    $chunkRoot = Join-Path $stagingRoot '.weight-chunks'
    New-Item -ItemType Directory -Path $chunkRoot -Force | Out-Null
    if (-not (Test-Path -LiteralPath $weightFinal -PathType Leaf)) {
        Remove-Item -LiteralPath $weightPartial -Force -ErrorAction SilentlyContinue
        $url = "https://drive.usercontent.google.com/download?id=$weightDriveId&export=download&confirm=t"
        $chunkIndex = 0
        for ($offset = 0L; $offset -lt $weightBytes; $offset += $chunkBytes) {
            $end = [Math]::Min($weightBytes - 1, $offset + $chunkBytes - 1)
            $expectedChunkBytes = $end - $offset + 1
            $chunk = Join-Path $chunkRoot ('{0:D5}.part' -f $chunkIndex)
            if (Test-Path -LiteralPath $chunk -PathType Leaf) {
                if ((Get-Item -LiteralPath $chunk).Length -eq $expectedChunkBytes) { $chunkIndex++; continue }
                Remove-Item -LiteralPath $chunk -Force
            }
            for ($attempt = 1; $attempt -le 3; $attempt++) {
                Write-Status 'transferring' (Get-DownloadedBytes) "Downloading HoVer-Net weight chunk $($chunkIndex + 1) (attempt $attempt of 3)."
                $headerPath = "$chunk.headers.partial"
                Remove-Item -LiteralPath $headerPath -Force -ErrorAction SilentlyContinue
                $process = Start-Process $curl -ArgumentList @('--fail','--location','--silent','--show-error','--remove-on-error','--range',"$offset-$end",'--dump-header',('"'+$headerPath+'"'),'--output',('"'+$chunk+'.partial"'),$url) -PassThru -NoNewWindow
                while (-not $process.HasExited) {
                    Write-Status 'transferring' (Get-DownloadedBytes) "Downloading HoVer-Net weight chunk $($chunkIndex + 1) (attempt $attempt of 3)."
                    Start-Sleep -Seconds 15
                    $process.Refresh()
                }
                $process.WaitForExit()
                $expectedContentRange = "bytes $offset-$end/$weightBytes"
                $hasExpectedContentRange = (Test-Path -LiteralPath $headerPath -PathType Leaf) -and
                    ((Get-Content -LiteralPath $headerPath -Raw) -match
                        ('(?im)^Content-Range:\s*' + [Regex]::Escape($expectedContentRange) + '\s*$'))
                if ($hasExpectedContentRange -and (Test-Path -LiteralPath "$chunk.partial" -PathType Leaf) -and
                        (Get-Item -LiteralPath "$chunk.partial").Length -eq $expectedChunkBytes) {
                    Move-Item -LiteralPath "$chunk.partial" -Destination $chunk -Force
                    Remove-Item -LiteralPath $headerPath -Force -ErrorAction SilentlyContinue
                    break
                }
                Remove-Item -LiteralPath "$chunk.partial" -Force -ErrorAction SilentlyContinue
                Remove-Item -LiteralPath $headerPath -Force -ErrorAction SilentlyContinue
                if ($attempt -lt 3) { Start-Sleep -Seconds $(if ($attempt -eq 1) { 5 } else { 30 }) }
            }
            if (-not (Test-Path -LiteralPath $chunk -PathType Leaf) -or
                    (Get-Item -LiteralPath $chunk).Length -ne $expectedChunkBytes) {
                throw "HoVer-Net weight chunk transfer failed: bytes $offset-$end"
            }
            $chunkIndex++
        }

        $output = [IO.File]::Open($weightPartial, [IO.FileMode]::Create, [IO.FileAccess]::Write, [IO.FileShare]::None)
        try {
            Get-ChildItem -LiteralPath $chunkRoot -Filter '*.part' -File | Sort-Object Name | ForEach-Object {
                $input = [IO.File]::OpenRead($_.FullName)
                try { $input.CopyTo($output) } finally { $input.Dispose() }
            }
        } finally { $output.Dispose() }
        if ((Get-Item -LiteralPath $weightPartial).Length -ne $weightBytes) {
            throw 'HoVer-Net weight assembled size mismatch.'
        }
        $weightSha256 = (Get-FileHash $weightPartial -Algorithm SHA256).Hash.ToLowerInvariant()
        Move-Item -LiteralPath $weightPartial -Destination $weightFinal -Force
        Remove-Item -LiteralPath $chunkRoot -Recurse -Force
    } else {
        if ((Get-Item -LiteralPath $weightFinal).Length -ne $weightBytes) {
            throw 'Existing staged HoVer-Net weight size changed.'
        }
        $weightSha256 = (Get-FileHash -LiteralPath $weightFinal -Algorithm SHA256).Hash.ToLowerInvariant()
    }

    if (-not (Test-Path -LiteralPath $runtimeManifest -PathType Leaf)) {
        throw 'The checksum-pinned shared CUDA runtime candidate is unavailable.'
    }
    $runtimeSha256 = (Get-FileHash -LiteralPath $runtimeManifest -Algorithm SHA256).Hash.ToLowerInvariant()
    $actualBytes = Directory-Bytes $stagingRoot
    $otherModelBytes = (Directory-Bytes $modelsRoot) - $actualBytes
    if ($actualBytes -gt $reservationBytes -or $actualBytes -gt $modelLimit - $otherModelBytes) {
        throw 'The acquired HoVer-Net candidate exceeded its model quota reservation.'
    }

    Write-JsonAtomic (Join-Path $stagingRoot 'candidate-ledger.json') ([ordered]@{
        schema='pathlab.model-candidate-acquisition/1';candidateId=$candidateId;version=$candidateVersion
        capability='cell-instance-detection';mode='fast';typeInfo='monusac-research-categories'
        code=[ordered]@{repository=$repository;commit=$commit;archive=(Split-Path -Leaf $codeArchive);sha256=$codeSha256;license='MIT'}
        weights=[ordered]@{fileName=$weightName;driveId=$weightDriveId;bytes=$weightBytes;sha256=$weightSha256;license='CC-BY-NC-SA-4.0';upstreamChecksumAvailable=$false}
        codeAndWeightLicensesReviewedSeparately=$true
        permittedUse='private-research-restricted';atlasResearchEligible=$true;atlasCleanEligible=$false
        sharedRuntimeCandidate='he-dinov2-small-v1/1';sharedRuntimeManifestSha256=$runtimeSha256
        runtimeCopiedIntoCandidate=$false;runtimeCompatibilityStatus='not_evaluable'
        qualificationStatus='not_evaluable';qualificationReason='RUNTIME_ADAPTATION_AND_HELD_OUT_EXECUTION_PENDING'
        analysisNetwork='disabled';activationEligible=$false
        frozenAt=[DateTimeOffset]::UtcNow.ToString('o')
    })
    Write-Status 'validating' $weightBytes 'Publishing the immutable restricted-research candidate ledger.'
    New-Item -ItemType Directory -Path (Split-Path -Parent $candidateRoot) -Force | Out-Null
    Move-Item -LiteralPath $stagingRoot -Destination $candidateRoot
    Remove-Item -LiteralPath $reservationPath -Force
    Write-Status 'completed' $weightBytes 'Official code and MoNuSAC fast weights are checksum-frozen; runtime adaptation and qualification remain not_evaluable.'
    Finish-Task
} catch {
    Write-Status 'failed' (Get-DownloadedBytes) $_.Exception.Message
    Finish-Task
    throw
}
