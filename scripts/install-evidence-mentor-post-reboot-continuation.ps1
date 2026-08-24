[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Administrator approval is required to install the post-reboot continuation.'
}

$taskName = 'PathLabEvidenceMentorPostRepair'
$programRoot = 'C:\ProgramData\PathLab\EvidenceMentor'
$continuation = Join-Path $programRoot 'Complete-PathLab-EvidenceMentor-PostRepair.ps1'
New-Item -ItemType Directory -Path $programRoot -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'complete-evidence-mentor-after-reboot.ps1') `
    -Destination $continuation -Force
$action = New-ScheduledTaskAction -Execute 'powershell.exe' `
    -Argument "-NoProfile -ExecutionPolicy Bypass -File `"$continuation`""
$trigger = New-ScheduledTaskTrigger -AtStartup
$trigger.Delay = 'PT2M'
$taskPrincipal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -ExecutionTimeLimit (New-TimeSpan -Minutes 30) `
    -MultipleInstances IgnoreNew
Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Principal $taskPrincipal `
    -Settings $settings -Description 'Complete fail-closed PathLab 2.1.1 firewall and service acceptance after Windows repairs load.' `
    -Force | Out-Null
Get-ScheduledTask -TaskName $taskName | Select-Object TaskName,State
