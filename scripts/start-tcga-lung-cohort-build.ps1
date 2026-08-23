[CmdletBinding()]
param(
    [ValidateSet('Start','Run','Status')] [string] $Action = 'Start',
    [string] $StateRoot = 'D:\PathLabData\EvidenceMentor\state'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$taskName = 'PathLabTcgaLungCohortBuild'
$state = [IO.Path]::GetFullPath($StateRoot)
$root = Join-Path $state 'acquisition\tcga-luad-lusc-he-20x2-v1\cohort-build'
$statusPath = Join-Path $root 'status.json'
$acquisitionStatusPath = Join-Path $state 'acquisition\tcga-luad-lusc-he-20x2-v1\status.json'
$cohortPath = Join-Path $state 'derived\qualification-prepared\tcga-luad-lusc-lung-20x2-v1\cohort.json'

function Write-Status([string] $StateValue, [string] $Detail) {
    if (-not (Test-Path -LiteralPath $root -PathType Container)) {
        New-Item -ItemType Directory -Path $root -Force | Out-Null
    }
    $value = [ordered]@{
        schema='pathlab.cohort-build-status/1';datasetId='tcga-luad-lusc-he-20x2-v1'
        state=$StateValue;detail=$Detail;updatedAt=[DateTimeOffset]::UtcNow.ToString('o')
    }
    [IO.File]::WriteAllText("$statusPath.partial", (($value|ConvertTo-Json -Depth 6)+"`n"),
        [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath "$statusPath.partial" -Destination $statusPath -Force
}

if ($Action -eq 'Status') {
    if (Test-Path -LiteralPath $statusPath -PathType Leaf) { Get-Content -LiteralPath $statusPath -Raw }
    else { '{"schema":"pathlab.cohort-build-status/1","state":"not_started"}' }
    return
}
if (-not (Test-Path -LiteralPath $root -PathType Container)) {
    New-Item -ItemType Directory -Path $root -Force | Out-Null
}

if ($Action -eq 'Start') {
    $builderSource = Join-Path $PSScriptRoot 'build-tcga-lung-dinov2-cohort.ps1'
    if (-not (Test-Path -LiteralPath $builderSource -PathType Leaf)) { throw 'TCGA cohort builder is unavailable.' }
    $durableLauncher = Join-Path $root 'start-tcga-lung-cohort-build.ps1'
    $durableBuilder = Join-Path $root 'build-tcga-lung-dinov2-cohort.ps1'
    Copy-Item -LiteralPath $PSCommandPath -Destination $durableLauncher -Force
    Copy-Item -LiteralPath $builderSource -Destination $durableBuilder -Force
    $taskAction = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument `
        "-NoProfile -ExecutionPolicy Bypass -File `"$durableLauncher`" -Action Run -StateRoot `"$state`""
    $trigger = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) `
        -RepetitionInterval (New-TimeSpan -Minutes 5) -RepetitionDuration (New-TimeSpan -Days 7)
    $principal = New-ScheduledTaskPrincipal -UserId ([Security.Principal.WindowsIdentity]::GetCurrent().Name) `
        -LogonType Interactive -RunLevel Limited
    $settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -MultipleInstances IgnoreNew `
        -ExecutionTimeLimit (New-TimeSpan -Hours 12)
    Register-ScheduledTask -TaskName $taskName -Action $taskAction -Trigger $trigger -Principal $principal `
        -Settings $settings -Description 'Build the offline checksum-bound TCGA lung cohort after acquisition.' -Force | Out-Null
    Write-Status 'waiting' 'Waiting for the checksum-verified TCGA acquisition.'
    Start-ScheduledTask -TaskName $taskName
    Get-Content -LiteralPath $statusPath -Raw
    return
}

if (-not (Test-Path -LiteralPath $acquisitionStatusPath -PathType Leaf)) {
    Write-Status 'waiting' 'TCGA acquisition status is unavailable.'
    return
}
$acquisition = Get-Content -LiteralPath $acquisitionStatusPath -Raw | ConvertFrom-Json
if ($acquisition.state -ne 'completed') {
    Write-Status 'waiting' "TCGA acquisition is $($acquisition.state)."
    return
}
if (Test-Path -LiteralPath $cohortPath -PathType Leaf) {
    Write-Status 'completed' "Existing immutable cohort: $cohortPath"
    Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue
    return
}
Write-Status 'building' 'Extracting offline tissue-aware coordinate-bound tiles.'
try {
    $endpoint = Get-Content -LiteralPath (Join-Path $state 'endpoint.json') -Raw | ConvertFrom-Json
    $token = [IO.File]::ReadAllText((Join-Path $state 'ipc-token')).Trim()
    $headers = @{Authorization="Bearer $token"}
    $null = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$($endpoint.port)/v1/control/pause" `
        -Headers $headers
    try {
        $runner = Invoke-RestMethod -Method Get -Uri "http://127.0.0.1:$($endpoint.port)/v1/status" `
            -Headers $headers
        if ([int]$runner.queue.active -ne 0) { throw 'The runner still has active jobs.' }
        $derivedUsed = [long]$runner.quota.derived.usedBytes
        $derivedRoot = Join-Path $state 'derived'
        $currentIdentity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
        $derivedAcl = Get-Acl -LiteralPath $derivedRoot
        $derivedSddl = $derivedAcl.Sddl
        try {
            $temporaryRule = New-Object Security.AccessControl.FileSystemAccessRule(
                $currentIdentity, 'Modify', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
            $derivedAcl.AddAccessRule($temporaryRule) | Out-Null
            Set-Acl -LiteralPath $derivedRoot -AclObject $derivedAcl
            & (Join-Path $root 'build-tcga-lung-dinov2-cohort.ps1') -StateRoot $state `
                -DerivedUsedBytes $derivedUsed
        } finally {
            $restoredAcl = New-Object Security.AccessControl.DirectorySecurity
            $restoredAcl.SetSecurityDescriptorSddlForm($derivedSddl)
            Set-Acl -LiteralPath $derivedRoot -AclObject $restoredAcl
        }
    } finally {
        $null = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$($endpoint.port)/v1/control/resume" `
            -Headers $headers
        Remove-Variable token,headers -ErrorAction SilentlyContinue
    }
    if (-not (Test-Path -LiteralPath $cohortPath -PathType Leaf)) { throw 'Cohort builder returned without a manifest.' }
    $sha = (Get-FileHash -LiteralPath $cohortPath -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Status 'completed' "Immutable cohort SHA256: $sha"
    Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue
} catch {
    Write-Status 'failed' $_.Exception.Message
    throw
}
