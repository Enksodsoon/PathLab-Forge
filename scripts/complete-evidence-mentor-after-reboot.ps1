[CmdletBinding()]
param(
    [string] $StateRoot = 'D:\PathLabData\EvidenceMentor\state',
    [string] $ProgramRoot = 'C:\ProgramData\PathLab\EvidenceMentor',
    [string] $DistributionPath = 'C:\Users\enkso\.codex\worktrees\evidence-mentor\forge\build\install\pathlab-forge',
    [string] $JavaHome = 'C:\Users\enkso\.gradle\jdks\eclipse_adoptium-17-amd64-windows.2'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$version = '2.1.1'
$taskName = 'PathLabEvidenceMentorPostRepair'
$repository = Split-Path -Parent $PSScriptRoot
$acceptanceRoot = Join-Path $StateRoot 'acceptance\post-reboot-2.1.1'
$resultPath = Join-Path $acceptanceRoot 'result.json'
$logPath = Join-Path $acceptanceRoot 'continuation.log'
New-Item -ItemType Directory -Path $acceptanceRoot -Force | Out-Null

function Write-Result([string] $Status, [string] $Detail) {
    $value = [ordered]@{
        schema = 'pathlab.post-reboot-continuation/1'
        version = $version
        status = $Status
        detail = $Detail
        completedAt = [DateTimeOffset]::UtcNow.ToString('o')
    } | ConvertTo-Json -Depth 6
    $partial = "$resultPath.partial"
    [IO.File]::WriteAllText($partial, $value + "`n", [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $partial -Destination $resultPath -Force
}

function Set-Claims([bool] $Accepting) {
    $endpoint = Get-Content -LiteralPath (Join-Path $StateRoot 'endpoint.json') -Raw | ConvertFrom-Json
    $token = [IO.File]::ReadAllText((Join-Path $StateRoot 'ipc-token')).Trim()
    $action = if ($Accepting) { 'resume' } else { 'pause' }
    Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$($endpoint.port)/v1/control/$action" `
        -Headers @{ Authorization = "Bearer $token" } -ContentType 'application/json' -Body '{}' | Out-Null
}

Start-Transcript -LiteralPath $logPath -Append | Out-Null
try {
    try { Set-Claims $false } catch { }
    & (Join-Path $repository 'scripts\evidence-mentor-service.ps1') -Action Upgrade -Version $version `
        -DistributionPath $DistributionPath -JavaHome $JavaHome -ProgramRoot $ProgramRoot -StateRoot $StateRoot

    $runtime = Join-Path $ProgramRoot "runtime\$version"
    $expectedPrograms = @((Join-Path $runtime 'jre\bin\java.exe'))
    $modelRoot = Join-Path $StateRoot 'models'
    if (Test-Path -LiteralPath $modelRoot) {
        $expectedPrograms += Get-ChildItem -LiteralPath $modelRoot -Filter '*.exe' -File -Recurse |
            Select-Object -ExpandProperty FullName
    }
    $expectedPrograms = @($expectedPrograms | ForEach-Object { [IO.Path]::GetFullPath($_).ToLowerInvariant() } |
        Sort-Object -Unique)
    $rules = @(Get-NetFirewallRule -DisplayName "PathLab Evidence Mentor outbound deny $version *" -ErrorAction Stop)
    $actualPrograms = @($rules | Get-NetFirewallApplicationFilter | Select-Object -ExpandProperty Program |
        ForEach-Object { [IO.Path]::GetFullPath($_).ToLowerInvariant() } | Sort-Object -Unique)
    if ($rules.Count -ne $expectedPrograms.Count -or
            (Compare-Object -ReferenceObject $expectedPrograms -DifferenceObject $actualPrograms)) {
        throw 'Installed outbound-deny rules do not exactly cover the analysis executables.'
    }

    & (Join-Path $repository 'scripts\test-evidence-mentor-service.ps1') -Mode Inspect `
        -ProgramRoot $ProgramRoot -StateRoot $StateRoot -ReportRoot $acceptanceRoot
    if ($LASTEXITCODE -ne 0) { throw "Post-reboot service acceptance failed with exit code $LASTEXITCODE." }

    Set-Claims $true
    Write-Result 'PASS' 'Firewall rules, service health, and non-reboot acceptance passed; claims resumed.'
    Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue
} catch {
    try { Set-Claims $false } catch { }
    Write-Result 'FAIL' $_.Exception.Message
    throw
} finally {
    Stop-Transcript | Out-Null
}
