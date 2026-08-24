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
    [string]$StateRoot
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
$endpointPath = Join-Path $state 'endpoint.json'
if (-not (Test-Path -LiteralPath $tokenPath -PathType Leaf) -or -not (Test-Path -LiteralPath $endpointPath -PathType Leaf)) {
    throw 'Evidence Mentor runner is not installed or has not started.'
}
$endpoint = Get-Content -LiteralPath $endpointPath -Raw | ConvertFrom-Json
if ($endpoint.schema -ne 'pathlab.runner-endpoint/1' -or $endpoint.port -lt 1 -or $endpoint.port -gt 65535) {
    throw 'Evidence Mentor endpoint is invalid.'
}
$statePrefix = $state.TrimEnd('\') + '\'
foreach ($input in @($source.FullName, $preview.FullName, $pack.FullName)) {
    if (-not $input.StartsWith($statePrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "LocalService cannot read an input outside the protected Evidence Mentor state root: $input"
    }
}
$jobId = [Guid]::NewGuid().ToString()
$requestRoot = Join-Path $state 'requests'
New-Item -ItemType Directory -Path $requestRoot -Force | Out-Null
$requestPath = Join-Path $requestRoot "$jobId.json"
$request = [ordered]@{
    schema = 'pathlab.evidence-job/2'
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
$partial = "$requestPath.partial"
[IO.File]::WriteAllText($partial, (($request | ConvertTo-Json -Depth 4) + "`n"),
    [Text.UTF8Encoding]::new($false))
Move-Item -LiteralPath $partial -Destination $requestPath
$token = [IO.File]::ReadAllText($tokenPath).Trim()
$headers = @{ Authorization = "Bearer $token" }
Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$($endpoint.port)/v1/jobs" -Headers $headers `
    -ContentType 'application/json' -Body (@{ id = $jobId; requestPath = $requestPath } | ConvertTo-Json)
