[CmdletBinding(SupportsShouldProcess)]
param(
    [Parameter(Mandatory = $true)]
    [string]$RuntimeRoot,
    [string]$StateRoot,
    [int]$Port = 8765,
    [switch]$Uninstall
)

$ErrorActionPreference = 'Stop'
$taskName = 'PathLab Evidence Mentor Runner'
$runtime = [IO.Path]::GetFullPath($RuntimeRoot)
if (-not $StateRoot) {
    $StateRoot = if (Test-Path -LiteralPath 'D:\PathLabData' -PathType Container) {
        'D:\PathLabData\EvidenceMentor\state'
    } else {
        Join-Path $env:LOCALAPPDATA 'PathLab\EvidenceMentor\state'
    }
}
$state = [IO.Path]::GetFullPath($StateRoot)

if ($Uninstall) {
    if ($PSCmdlet.ShouldProcess($taskName, 'Unregister per-user scheduled task')) {
        Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue
    }
    return
}

$lib = Join-Path $runtime 'lib\*'
if (-not (Test-Path -LiteralPath (Split-Path $lib) -PathType Container)) {
    throw "Versioned Forge runtime missing lib directory: $lib"
}
$java = if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\javaw.exe'))) {
    Join-Path $env:JAVA_HOME 'bin\javaw.exe'
} else {
    (Get-Command javaw.exe -ErrorAction Stop).Source
}

if ($PSCmdlet.ShouldProcess($state, 'Create protected Evidence Mentor state')) {
    New-Item -ItemType Directory -Path $state -Force | Out-Null
    $token = Join-Path $state 'ipc-token'
    if (-not (Test-Path -LiteralPath $token -PathType Leaf)) {
        $bytes = [byte[]]::new(32)
        [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
        [IO.File]::WriteAllText($token, [Convert]::ToBase64String($bytes))
    }
    & icacls.exe $token /inheritance:r /grant:r "${env:USERNAME}:(R,W)" | Out-Null
}

$arguments = @(
    '-Xmx16g'
    '-cp'
    ('"{0}"' -f $lib)
    'org.pathlab.forge.evidence.EvidenceMentorRunner'
    '--state'
    ('"{0}"' -f $state)
    '--port'
    $Port
) -join ' '
$action = New-ScheduledTaskAction -Execute $java -Argument $arguments
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -RestartCount 3 `
    -RestartInterval (New-TimeSpan -Minutes 1) -ExecutionTimeLimit ([TimeSpan]::Zero)
$principal = New-ScheduledTaskPrincipal -UserId $env:USERNAME -LogonType Interactive `
    -RunLevel Limited

if ($PSCmdlet.ShouldProcess($taskName, 'Register and start per-user autonomous runner')) {
    Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger `
        -Settings $settings -Principal $principal -Force | Out-Null
    Start-ScheduledTask -TaskName $taskName
}
