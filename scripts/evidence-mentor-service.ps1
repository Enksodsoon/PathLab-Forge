[CmdletBinding(SupportsShouldProcess)]
param(
    [Parameter(Mandatory)] [ValidateSet('Install','Upgrade','Uninstall','Status')] [string] $Action,
    [string] $Version = '2.0.2',
    [string] $DistributionPath,
    [string] $JavaHome,
    [string] $ProgramRoot = 'C:\ProgramData\PathLab\EvidenceMentor',
    [string] $StateRoot = 'D:\PathLabData\EvidenceMentor\state'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$serviceName = 'PathLabEvidenceMentor'
$winSwVersion = '2.12.0'
$winSwUri = "https://github.com/winsw/winsw/releases/download/v$winSwVersion/WinSW-x64.exe"
$winSwSha256 = '05b82d46ad331cc16bdc00de5c6332c1ef818df8ceefcd49c726553209b3a0da'
$wrapperPath = Join-Path $ProgramRoot 'PathLabEvidenceMentor.exe'
$configPath = Join-Path $ProgramRoot 'PathLabEvidenceMentor.xml'
$activeVersionPath = Join-Path $ProgramRoot 'active-version.txt'
$runtimeRoot = Join-Path $ProgramRoot 'runtime'
$logRoot = Join-Path $StateRoot 'logs'

function Assert-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Administrator approval is required to install or upgrade the PathLab service.'
    }
}

function Write-AtomicText([string] $Path, [string] $Content) {
    $partial = "$Path.partial"
    [IO.File]::WriteAllText($partial, $Content, [Text.UTF8Encoding]::new($false))
    if (Test-Path -LiteralPath $Path -PathType Leaf) {
        [IO.File]::Replace($partial, $Path, $null)
    } else {
        [IO.File]::Move($partial, $Path)
    }
}

function Ensure-WinSW {
    if (Test-Path -LiteralPath $wrapperPath -PathType Leaf) {
        $actual = (Get-FileHash -LiteralPath $wrapperPath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actual -eq $winSwSha256) { return }
        throw 'Installed WinSW checksum does not match the pinned release.'
    }
    $partial = "$wrapperPath.partial"
    Invoke-WebRequest -UseBasicParsing -Uri $winSwUri -OutFile $partial
    $actual = (Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $winSwSha256) { throw 'Downloaded WinSW checksum does not match the pinned release.' }
    Move-Item -LiteralPath $partial -Destination $wrapperPath
}

function Grant-PathLabAccess([string] $InstallingUser) {
    & icacls.exe $ProgramRoot /inheritance:r /grant:r 'SYSTEM:(OI)(CI)F' 'Administrators:(OI)(CI)F' "NT SERVICE\${serviceName}:(OI)(CI)RX" "${InstallingUser}:(OI)(CI)RX" | Out-Null
    & icacls.exe $StateRoot /inheritance:r /grant:r 'SYSTEM:(OI)(CI)F' 'Administrators:(OI)(CI)F' "NT SERVICE\${serviceName}:(OI)(CI)M" "${InstallingUser}:(RX)" | Out-Null
    foreach ($operatorPath in @('inputs','requests','models','artifacts')) {
        $resolved = Join-Path $StateRoot $operatorPath
        & icacls.exe $resolved /inheritance:r /grant:r 'SYSTEM:(OI)(CI)F' 'Administrators:(OI)(CI)F' "NT SERVICE\${serviceName}:(OI)(CI)M" "${InstallingUser}:(OI)(CI)M" | Out-Null
    }
    foreach ($serviceOnlyPath in @('checkpoints','signing')) {
        $resolved = Join-Path $StateRoot $serviceOnlyPath
        & icacls.exe $resolved /inheritance:r /grant:r 'SYSTEM:(OI)(CI)F' 'Administrators:(OI)(CI)F' "NT SERVICE\${serviceName}:(OI)(CI)M" | Out-Null
    }
    & icacls.exe $logRoot /inheritance:r /grant:r 'SYSTEM:(OI)(CI)F' 'Administrators:(OI)(CI)F' "NT SERVICE\${serviceName}:(OI)(CI)M" "${InstallingUser}:(OI)(CI)R" | Out-Null
    foreach ($sensitive in @((Join-Path $StateRoot 'ipc-token'), (Join-Path $StateRoot 'endpoint.json'))) {
        if (Test-Path -LiteralPath $sensitive) {
            & icacls.exe $sensitive /inheritance:r /grant:r 'SYSTEM:F' 'Administrators:F' "NT SERVICE\${serviceName}:R" "${InstallingUser}:R" | Out-Null
        }
    }
}

function Enable-ServiceSid {
    & sc.exe sidtype $serviceName unrestricted | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Windows could not enable the PathLab service SID.'
    }
}

function New-ServiceConfig([string] $RuntimePath) {
    $java = [Security.SecurityElement]::Escape((Join-Path $RuntimePath 'jre\bin\java.exe'))
    $classpath = [Security.SecurityElement]::Escape((Join-Path $RuntimePath 'app\lib\*'))
    $state = [Security.SecurityElement]::Escape($StateRoot)
    $logs = [Security.SecurityElement]::Escape($logRoot)
    return @"
<service>
  <id>$serviceName</id>
  <name>PathLab Evidence Mentor</name>
  <description>Offline local pathology research evidence worker and operations dashboard.</description>
  <executable>$java</executable>
  <arguments>-Xrs -Xmx16g -cp &quot;$classpath&quot; org.pathlab.forge.evidence.EvidenceMentorRunner --state &quot;$state&quot; --port 0</arguments>
  <workingdirectory>$([Security.SecurityElement]::Escape($RuntimePath))</workingdirectory>
  <startmode>Automatic</startmode>
  <delayedAutoStart />
  <serviceaccount><domain>NT AUTHORITY</domain><user>LocalService</user></serviceaccount>
  <onfailure action="restart" delay="1 min" />
  <onfailure action="restart" delay="5 min" />
  <onfailure action="restart" delay="15 min" />
  <resetfailure>1 day</resetfailure>
  <stoptimeout>30 sec</stoptimeout>
  <logpath>$logs</logpath>
  <log mode="roll-by-size"><sizeThreshold>10485760</sizeThreshold><keepFiles>8</keepFiles></log>
  <env name="PATHLAB_EVIDENCE_STATE" value="$state" />
  <env name="PATHLAB_ANALYSIS_NETWORK" value="disabled" />
  <env name="HF_HUB_OFFLINE" value="1" />
  <env name="TRANSFORMERS_OFFLINE" value="1" />
</service>
"@
}

function Install-FirewallRules([string] $RuntimePath) {
    Get-NetFirewallRule -DisplayName 'PathLab Evidence Mentor outbound deny*' -ErrorAction SilentlyContinue | Remove-NetFirewallRule
    $executables = @((Join-Path $RuntimePath 'jre\bin\java.exe'))
    $modelRoot = Join-Path $StateRoot 'models'
    if (Test-Path -LiteralPath $modelRoot) {
        $executables += Get-ChildItem -LiteralPath $modelRoot -Filter '*.exe' -File -Recurse | Select-Object -ExpandProperty FullName
    }
    $index = 0
    foreach ($executable in $executables | Select-Object -Unique) {
        if (Test-Path -LiteralPath $executable -PathType Leaf) {
            New-NetFirewallRule -DisplayName "PathLab Evidence Mentor outbound deny $index" -Direction Outbound -Action Block -Program $executable -Profile Any | Out-Null
            $index++
        }
    }
}

function Wait-RunnerHealth([int] $Seconds = 30) {
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($Seconds)
    do {
        Start-Sleep -Milliseconds 500
        $endpointPath = Join-Path $StateRoot 'endpoint.json'
        $tokenPath = Join-Path $StateRoot 'ipc-token'
        if ((Test-Path -LiteralPath $endpointPath) -and (Test-Path -LiteralPath $tokenPath)) {
            try {
                $endpoint = Get-Content -LiteralPath $endpointPath -Raw | ConvertFrom-Json
                if ($endpoint.schema -ne 'pathlab.runner-endpoint/1') { continue }
                $headers = @{ Authorization = 'Bearer ' + (Get-Content -LiteralPath $tokenPath -Raw).Trim() }
                $health = Invoke-RestMethod -Uri "http://127.0.0.1:$($endpoint.port)/health" -Headers $headers -TimeoutSec 2
                if ($health.status -eq 'ready') { return $true }
            } catch { }
        }
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    return $false
}

function Stage-Runtime {
    if (-not $DistributionPath -or -not (Test-Path -LiteralPath $DistributionPath -PathType Container)) { throw 'DistributionPath must reference a completed installDist directory.' }
    if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe') -PathType Leaf)) { throw 'JavaHome must reference a Java 17 runtime.' }
    $target = Join-Path $runtimeRoot $Version
    if (Test-Path -LiteralPath $target) { throw "Runtime version already exists: $target" }
    New-Item -ItemType Directory -Path (Join-Path $target 'app') -Force | Out-Null
    Copy-Item -Path (Join-Path $DistributionPath '*') -Destination (Join-Path $target 'app') -Recurse -Force
    Copy-Item -LiteralPath $JavaHome -Destination (Join-Path $target 'jre') -Recurse -Force
    return $target
}

if ($Action -eq 'Status') {
    Get-CimInstance Win32_Service -Filter "Name='$serviceName'" -ErrorAction SilentlyContinue | Select-Object Name, State, StartMode, StartName
    if (Test-Path -LiteralPath (Join-Path $StateRoot 'endpoint.json')) { Get-Content -LiteralPath (Join-Path $StateRoot 'endpoint.json') -Raw }
    return
}

if (-not $WhatIfPreference) { Assert-Administrator }
if ($Action -eq 'Uninstall') {
    if ($PSCmdlet.ShouldProcess($serviceName, 'Uninstall service and outbound-deny rules while preserving state and model data')) {
        if (Test-Path -LiteralPath $wrapperPath) {
            & $wrapperPath stop 2>$null
            & $wrapperPath uninstall
        }
        Get-NetFirewallRule -DisplayName 'PathLab Evidence Mentor outbound deny*' -ErrorAction SilentlyContinue | Remove-NetFirewallRule
        $shortcutPath = Join-Path ([Environment]::GetFolderPath('CommonStartMenu')) 'Programs\PathLab Evidence Mentor Dashboard.lnk'
        if (Test-Path -LiteralPath $shortcutPath) { Remove-Item -LiteralPath $shortcutPath -Force }
    }
    return
}

$installingUser = [Security.Principal.WindowsIdentity]::GetCurrent().Name
if ($PSCmdlet.ShouldProcess($serviceName, "$Action autonomous service version $Version")) {
    $stateDirectories = @('inputs','requests','models','artifacts','checkpoints','signing','logs') | ForEach-Object { Join-Path $StateRoot $_ }
    New-Item -ItemType Directory -Path @($ProgramRoot, $runtimeRoot, $StateRoot) -Force | Out-Null
    New-Item -ItemType Directory -Path $stateDirectories -Force | Out-Null
    $previousVersion = if (Test-Path -LiteralPath $activeVersionPath) { (Get-Content -LiteralPath $activeVersionPath -Raw).Trim() } else { '' }
    $previousConfig = if (Test-Path -LiteralPath $configPath) { Get-Content -LiteralPath $configPath -Raw } else { '' }
    $runtimePath = Stage-Runtime
    Ensure-WinSW
    if ($Action -eq 'Upgrade' -and (Get-Service -Name $serviceName -ErrorAction SilentlyContinue)) { & $wrapperPath stop }
    Write-AtomicText $configPath (New-ServiceConfig $runtimePath)
    if (-not (Get-Service -Name $serviceName -ErrorAction SilentlyContinue)) { & $wrapperPath install }
    Enable-ServiceSid
    Grant-PathLabAccess $installingUser
    Install-FirewallRules $runtimePath
    & $wrapperPath start
    if (-not (Wait-RunnerHealth)) {
        & $wrapperPath stop 2>$null
        if ($previousConfig) {
            Write-AtomicText $configPath $previousConfig
            Install-FirewallRules (Join-Path $runtimeRoot $previousVersion)
            & $wrapperPath start
        }
        throw 'New runtime failed health checks; the previous configuration was restored.'
    }
    Write-AtomicText $activeVersionPath $Version
    Grant-PathLabAccess $installingUser
    $launcher = Join-Path $ProgramRoot 'Open-PathLab-Evidence-Dashboard.ps1'
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'open-evidence-dashboard.ps1') -Destination $launcher -Force
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'test-evidence-mentor-service.ps1') `
        -Destination (Join-Path $ProgramRoot 'Test-PathLab-Evidence-Service.ps1') -Force
    $shortcutPath = Join-Path ([Environment]::GetFolderPath('CommonStartMenu')) 'Programs\PathLab Evidence Mentor Dashboard.lnk'
    $shell = New-Object -ComObject WScript.Shell
    $shortcut = $shell.CreateShortcut($shortcutPath)
    $shortcut.TargetPath = 'powershell.exe'
    $shortcut.Arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$launcher`" -StateRoot `"$StateRoot`""
    $shortcut.WorkingDirectory = $ProgramRoot
    $shortcut.Save()
}
