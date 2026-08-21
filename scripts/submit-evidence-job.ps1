[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$SourcePath,
    [Parameter(Mandatory = $true)][string]$SlideRevision,
    [Parameter(Mandatory = $true)][string]$PreviewPath,
    [Parameter(Mandatory = $true)][int]$SourceWidth,
    [Parameter(Mandatory = $true)][int]$SourceHeight,
    [Parameter(Mandatory = $true)][string]$PackManifest,
    [ValidateSet('he', 'ihc_dab')][string]$Stain,
    [string]$Marker = 'generic',
    [string]$StateRoot,
    [int]$Port = 8765
)

$ErrorActionPreference = 'Stop'
if (-not $StateRoot) {
    $StateRoot = if (Test-Path -LiteralPath 'D:\PathLabData' -PathType Container) {
        'D:\PathLabData\EvidenceMentor\state'
    } else {
        Join-Path $env:LOCALAPPDATA 'PathLab\EvidenceMentor\state'
    }
}
$state = [IO.Path]::GetFullPath($StateRoot)
$source = Get-Item -LiteralPath $SourcePath
$preview = Get-Item -LiteralPath $PreviewPath
$pack = Get-Item -LiteralPath $PackManifest
$tokenPath = Join-Path $state 'ipc-token'
if (-not (Test-Path -LiteralPath $tokenPath -PathType Leaf)) {
    throw 'Evidence Mentor runner is not installed or has not started.'
}
$jobId = [Guid]::NewGuid().ToString()
$requestRoot = Join-Path $state 'requests'
New-Item -ItemType Directory -Path $requestRoot -Force | Out-Null
$requestPath = Join-Path $requestRoot "$jobId.json"
$request = [ordered]@{
    schema = 'pathlab.evidence-job/1'
    sourcePath = $source.FullName
    sourceSha256 = (Get-FileHash -LiteralPath $source.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    slideRevision = $SlideRevision
    previewPath = $preview.FullName
    sourceWidth = $SourceWidth
    sourceHeight = $SourceHeight
    packManifest = $pack.FullName
    stain = $Stain
    marker = $Marker.ToLowerInvariant()
}
$request | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $requestPath -Encoding utf8NoBOM
$token = [IO.File]::ReadAllText($tokenPath).Trim()
$headers = @{ Authorization = "Bearer $token" }
Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$Port/v1/jobs" -Headers $headers `
    -ContentType 'application/json' -Body (@{ id = $jobId; requestPath = $requestPath } | ConvertTo-Json)
