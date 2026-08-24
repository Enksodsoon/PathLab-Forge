[CmdletBinding()]
param(
    [string] $StateRoot = 'D:\PathLabData\EvidenceMentor\state',
    [string] $ProgramRoot = 'C:\ProgramData\PathLab\EvidenceMentor'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Administrator approval is required to repair the installed service metadata.'
}

$configPath = Join-Path $ProgramRoot 'PathLabEvidenceMentor.xml'
$endpointPath = Join-Path $StateRoot 'endpoint.json'
$tokenPath = Join-Path $StateRoot 'ipc-token'
foreach ($required in @($configPath, $endpointPath, $tokenPath)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Required installed-state file is unavailable: $required"
    }
}

$config = Get-Content -LiteralPath $configPath -Raw
if ($config -notmatch '(?i)\\runtime\\([^\\<"]+)\\') {
    throw 'The installed service configuration does not identify a versioned runtime.'
}
$runtimeVersion = $Matches[1]
$endpoint = Get-Content -LiteralPath $endpointPath -Raw | ConvertFrom-Json
$token = [IO.File]::ReadAllText($tokenPath).Trim()
$status = Invoke-RestMethod -Uri "http://127.0.0.1:$($endpoint.port)/v1/status" `
    -Headers @{ Authorization = "Bearer $token" } -TimeoutSec 5
if ($endpoint.schema -ne 'pathlab.runner-endpoint/1' -or $endpoint.serviceVersion -ne $runtimeVersion -or
        $status.serviceVersion -ne $runtimeVersion -or $status.status -ne 'ready') {
    throw 'The runtime XML, endpoint, and live service version do not agree.'
}

$activeVersionPath = Join-Path $ProgramRoot 'active-version.txt'
$activePartial = "$activeVersionPath.partial"
[IO.File]::WriteAllText($activePartial, "$runtimeVersion`n", [Text.UTF8Encoding]::new($false))
Move-Item -LiteralPath $activePartial -Destination $activeVersionPath -Force

$launcherSource = Join-Path $PSScriptRoot 'open-evidence-dashboard.ps1'
$launcherTarget = Join-Path $ProgramRoot 'Open-PathLab-Evidence-Dashboard.ps1'
Copy-Item -LiteralPath $launcherSource -Destination $launcherTarget -Force

& (Join-Path $PSScriptRoot 'install-evidence-mentor-post-reboot-continuation.ps1')
$installedTask = Get-ScheduledTask -TaskName 'PathLabEvidenceMentorPostRepair' -ErrorAction Stop
if ($installedTask.Principal.UserId -ne 'SYSTEM' -or
        [string]$installedTask.Principal.RunLevel -ne 'Highest') {
    throw 'The post-reboot continuation task identity or run level is invalid.'
}

$resultRoot = Join-Path $StateRoot 'acceptance\installed-state-repair'
New-Item -ItemType Directory -Path $resultRoot -Force | Out-Null
$resultPath = Join-Path $resultRoot 'result.json'
$result = [ordered]@{
    schema = 'pathlab.installed-state-repair/1'
    status = 'PASS'
    runtimeVersion = $runtimeVersion
    launcherSha256 = (Get-FileHash -LiteralPath $launcherTarget -Algorithm SHA256).Hash.ToLowerInvariant()
    postRebootTask = 'PathLabEvidenceMentorPostRepair'
    postRebootTaskState = [string]$installedTask.State
    repairedAt = [DateTimeOffset]::UtcNow.ToString('o')
}
$partial = "$resultPath.partial"
[IO.File]::WriteAllText($partial, (($result | ConvertTo-Json -Depth 6) + "`n"),
    [Text.UTF8Encoding]::new($false))
Move-Item -LiteralPath $partial -Destination $resultPath -Force
$result
