[CmdletBinding(SupportsShouldProcess)]
param(
    [string] $RuntimeRoot,
    [string] $StateRoot,
    [int] $Port,
    [switch] $Uninstall
)

$ErrorActionPreference = 'Stop'
$legacyTask = 'PathLab Evidence Mentor Runner'
if ($Uninstall) {
    if ($PSCmdlet.ShouldProcess($legacyTask, 'Remove the obsolete per-user scheduled task')) {
        Unregister-ScheduledTask -TaskName $legacyTask -Confirm:$false -ErrorAction SilentlyContinue
    }
    return
}

throw 'Per-user Task Scheduler deployment was retired. Build installDist and use scripts\evidence-mentor-service.ps1 with administrator approval.'
