[CmdletBinding()]
param(
    [string] $StateRoot = 'D:\PathLabData\EvidenceMentor\state',
    [ValidateRange(1, 60)]
    [int] $RefreshSeconds = 2,
    [switch] $Once
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$acquisitionRoot = Join-Path ([IO.Path]::GetFullPath($StateRoot)) 'acquisition'

function Read-AcquisitionStatuses {
    if (-not (Test-Path -LiteralPath $acquisitionRoot -PathType Container)) { return @() }
    $results = foreach ($directory in Get-ChildItem -LiteralPath $acquisitionRoot -Directory) {
        $statusPath = Join-Path $directory.FullName 'status.json'
        if (-not (Test-Path -LiteralPath $statusPath -PathType Leaf)) { continue }
        try {
            $status = Get-Content -LiteralPath $statusPath -Raw | ConvertFrom-Json
            if ($status.schema -ne 'pathlab.acquisition-status/1' -or
                    [long]$status.completedBytes -lt 0 -or [long]$status.totalBytes -le 0 -or
                    [long]$status.completedBytes -gt [long]$status.totalBytes) { continue }
            [pscustomobject]@{
                Dataset = [string]$status.datasetId
                State = [string]$status.state
                Percent = '{0:N1}%' -f (100 * [double]$status.completedBytes / [double]$status.totalBytes)
                ProgressGiB = '{0:N2} / {1:N2}' -f ([double]$status.completedBytes / 1GB),
                    ([double]$status.totalBytes / 1GB)
                Detail = [string]$status.detail
                Updated = ([DateTimeOffset]::Parse([string]$status.updatedAt)).ToLocalTime().ToString('yyyy-MM-dd HH:mm:ss')
            }
        } catch {
            # Status files are replaced atomically; a transient read failure must not stop monitoring.
        }
    }
    return @($results | Sort-Object Dataset)
}

do {
    Clear-Host
    Write-Host 'PathLab Evidence Mentor - Data Acquisition' -ForegroundColor Cyan
    Write-Host "State root: $StateRoot"
    Write-Host "Updated:    $((Get-Date).ToString('yyyy-MM-dd HH:mm:ss'))"
    Write-Host ''
    $statuses = @(Read-AcquisitionStatuses)
    if ($statuses.Count -eq 0) {
        Write-Host 'No acquisition records are available.' -ForegroundColor Yellow
    } else {
        $statuses | Format-Table Dataset, State, Percent, ProgressGiB, Updated -AutoSize
        foreach ($status in $statuses) {
            Write-Host "$($status.Dataset): $($status.Detail)"
        }
    }
    if (-not $Once) {
        Write-Host ''
        Write-Host 'Refreshing automatically. Press Ctrl+C to close.' -ForegroundColor DarkGray
        Start-Sleep -Seconds $RefreshSeconds
    }
} while (-not $Once)
