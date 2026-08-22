[CmdletBinding()]
param(
    [string]$StateRoot = 'D:\PathLabData\EvidenceMentor\state'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$state = [IO.Path]::GetFullPath($StateRoot)
$modelRoot = [IO.Path]::GetFullPath((Join-Path $state 'models\he-dinov2-small-v1\1'))
$acceptanceRoot = [IO.Path]::GetFullPath((Join-Path $state 'acceptance\gpu-session0-v1'))
$manifestSource = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot `
    '..\src\main\resources\evidence-packs\he-dinov2-small-session0-acceptance-v1.json'))
$manifestTarget = Join-Path $modelRoot 'session0-acceptance-pack.json'

if (-not $modelRoot.StartsWith((Join-Path $state 'models') + [IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase) -or
        -not $acceptanceRoot.StartsWith((Join-Path $state 'acceptance') + [IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Session 0 acceptance paths escaped the protected state root.'
}
if (-not (Test-Path -LiteralPath $manifestSource -PathType Leaf)) {
    throw 'Bundled Session 0 acceptance manifest is unavailable.'
}

function Get-Sha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Write-Utf8([string]$Path, [string]$Value) {
    [IO.File]::WriteAllText($Path, $Value, [Text.UTF8Encoding]::new($false))
}

function Write-Json([string]$Path, [object]$Value) {
    Write-Utf8 $Path (($Value | ConvertTo-Json -Depth 12) + "`n")
}

$manifest = Get-Content -LiteralPath $manifestSource -Raw | ConvertFrom-Json
if ($manifest.schema -ne 'pathlab.ai-pack/1' -or
        $manifest.usageLimits.acceptanceOnly -ne $true -or
        $manifest.rights.allowedUse -ne 'benchmark-only' -or
        $manifest.validation.status -ne 'not-evaluable') {
    throw 'Session 0 acceptance manifest weakened its fail-closed usage limits.'
}
$artifactFiles = @{
    model = 'model.safetensors'
    config = 'config.json'
    preprocessor = 'preprocessor_config.json'
    worker = 'worker.exe'
    workerSource = 'worker.py'
    'runtime-manifest' = 'runtime-manifest.json'
}
foreach ($artifact in $manifest.artifacts) {
    if (-not $artifactFiles.ContainsKey([string]$artifact.name)) { continue }
    $path = Join-Path $modelRoot $artifactFiles[[string]$artifact.name]
    if (-not (Test-Path -LiteralPath $path -PathType Leaf) -or
            (Get-Sha256 $path) -ne [string]$artifact.sha256) {
        throw "Pinned Session 0 artifact is unavailable or changed: $($artifact.name)"
    }
}

$targetPartial = "$manifestTarget.partial"
if (Test-Path -LiteralPath $manifestTarget -PathType Leaf) {
    if ((Get-Sha256 $manifestTarget) -ne (Get-Sha256 $manifestSource)) {
        $installed = Get-Content -LiteralPath $manifestTarget -Raw | ConvertFrom-Json
        if ($installed.schema -ne 'pathlab.ai-pack/1' -or
                $installed.packId -ne $manifest.packId -or
                $installed.version -ne $manifest.version -or
                $installed.usageLimits.acceptanceOnly -ne $true -or
                $installed.rights.allowedUse -ne 'benchmark-only' -or
                $installed.validation.status -ne 'not-evaluable') {
            throw 'Refusing to replace a manifest that is not the same fail-closed acceptance pack.'
        }
        Copy-Item -LiteralPath $manifestSource -Destination $targetPartial
        $backup = "$manifestTarget.backup-$([Guid]::NewGuid().ToString('N'))"
        try {
            [IO.File]::Replace($targetPartial, $manifestTarget, $backup)
        } finally {
            if (Test-Path -LiteralPath $backup) { Remove-Item -LiteralPath $backup -Force }
        }
    }
} else {
    Copy-Item -LiteralPath $manifestSource -Destination $targetPartial
    Move-Item -LiteralPath $targetPartial -Destination $manifestTarget
}

$requestPath = Join-Path $acceptanceRoot 'request.json'
if (Test-Path -LiteralPath $requestPath -PathType Leaf) {
    $requestSha = Get-Sha256 $requestPath
    [pscustomobject]@{
        RequestPath = $requestPath
        RequestSha256 = $requestSha
        JobId = 'acceptance-' + $requestSha.Substring(0, 16)
        PackManifest = $manifestTarget
        PackManifestSha256 = Get-Sha256 $manifestTarget
    }
    return
}

$partialRoot = "$acceptanceRoot.partial-$PID"
if (Test-Path -LiteralPath $partialRoot) {
    throw 'A prior Session 0 acceptance staging directory requires review.'
}
New-Item -ItemType Directory -Path (Join-Path $partialRoot 'tiles') -Force | Out-Null
try {
    Add-Type -AssemblyName System.Drawing
    $sourcePath = Join-Path $partialRoot 'source.png'
    $source = [Drawing.Bitmap]::new(1024, 1024, [Drawing.Imaging.PixelFormat]::Format24bppRgb)
    try {
        $graphics = [Drawing.Graphics]::FromImage($source)
        try {
            $graphics.Clear([Drawing.Color]::FromArgb(238, 204, 222))
            $brushes = @(
                [Drawing.SolidBrush]::new([Drawing.Color]::FromArgb(84, 48, 132)),
                [Drawing.SolidBrush]::new([Drawing.Color]::FromArgb(164, 91, 139)),
                [Drawing.SolidBrush]::new([Drawing.Color]::FromArgb(111, 62, 151)),
                [Drawing.SolidBrush]::new([Drawing.Color]::FromArgb(197, 119, 157))
            )
            try {
                for ($quadrant = 0; $quadrant -lt 4; $quadrant++) {
                    $originX = ($quadrant % 2) * 512
                    $originY = [Math]::Floor($quadrant / 2) * 512
                    for ($y = 16; $y -lt 500; $y += 32) {
                        for ($x = 16; $x -lt 500; $x += 32) {
                            $size = 8 + (($x + $y + $quadrant) % 13)
                            $graphics.FillEllipse($brushes[$quadrant], $originX + $x, $originY + $y, $size, $size + 3)
                        }
                    }
                }
            } finally {
                foreach ($brush in $brushes) { $brush.Dispose() }
            }
        } finally { $graphics.Dispose() }
        $source.Save($sourcePath, [Drawing.Imaging.ImageFormat]::Png)
        $tiles = [Collections.Generic.List[object]]::new()
        for ($index = 0; $index -lt 4; $index++) {
            $x = ($index % 2) * 512
            $y = [Math]::Floor($index / 2) * 512
            $tile = $source.Clone([Drawing.Rectangle]::new($x, $y, 512, 512),
                [Drawing.Imaging.PixelFormat]::Format24bppRgb)
            try {
                $relative = "tiles/tile-$($index + 1).png"
                $tilePath = Join-Path $partialRoot $relative
                $tile.Save($tilePath, [Drawing.Imaging.ImageFormat]::Png)
                $tiles.Add([ordered]@{
                    id = "tile-$($index + 1)"
                    path = $relative
                    sha256 = Get-Sha256 $tilePath
                    x = [int]$x
                    y = [int]$y
                    width = 512
                    height = 512
                })
            } finally { $tile.Dispose() }
        }
    } finally { $source.Dispose() }

    $sourceSha = Get-Sha256 $sourcePath
    $samplePath = Join-Path $partialRoot 'sample.json'
    Write-Json $samplePath ([ordered]@{
        schema = 'pathlab.evidence-sample/1'
        source = 'pathlab-synthetic-session0-acceptance-v1'
        patientGroup = 'synthetic-no-patient'
        slideId = 'session0-p2000-v1'
        sha256 = $sourceSha
        license = 'PathLab synthetic acceptance fixture'
        taskLabel = 'synthetic-hardware-acceptance'
        permittedUse = 'private-research'
        bytes = (Get-Item -LiteralPath $sourcePath).Length
        grandfatheredReadOnly = $false
    })
    $tileCachePath = Join-Path $partialRoot 'tile-cache.json'
    Write-Json $tileCachePath ([ordered]@{
        schema = 'pathlab.tile-cache/1'
        source = [ordered]@{
            sha256 = $sourceSha
            slideRevision = "session0-p2000-v1:$sourceSha"
            width = 1024
            height = 1024
            sampleManifest = 'sample.json'
            sampleManifestSha256 = Get-Sha256 $samplePath
        }
        tilePixels = 512
        encoding = 'png'
        preprocessingInput = 'rgb-srgb-uint8'
        tiles = $tiles
    })
    Write-Json (Join-Path $partialRoot 'request.json') ([ordered]@{
        schema = 'pathlab.evidence-job/2'
        sourcePath = (Join-Path $acceptanceRoot 'source.png')
        sourceSha256 = $sourceSha
        slideRevision = "session0-p2000-v1:$sourceSha"
        previewPath = (Join-Path $acceptanceRoot 'source.png')
        sourceWidth = 1024
        sourceHeight = 1024
        packManifest = $manifestTarget
        stain = 'he'
        marker = 'generic'
        tileCacheManifest = (Join-Path $acceptanceRoot 'tile-cache.json')
        tileCacheManifestSha256 = Get-Sha256 $tileCachePath
    })
    Move-Item -LiteralPath $partialRoot -Destination $acceptanceRoot
} finally {
    if (Test-Path -LiteralPath $partialRoot) {
        $resolvedPartial = [IO.Path]::GetFullPath($partialRoot)
        if (-not $resolvedPartial.StartsWith((Join-Path $state 'acceptance') + [IO.Path]::DirectorySeparatorChar,
                [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Refusing to clean a staging path outside the acceptance root.'
        }
        Remove-Item -LiteralPath $resolvedPartial -Recurse -Force
    }
}

$requestSha = Get-Sha256 $requestPath
[pscustomobject]@{
    RequestPath = $requestPath
    RequestSha256 = $requestSha
    JobId = 'acceptance-' + $requestSha.Substring(0, 16)
    PackManifest = $manifestTarget
    PackManifestSha256 = Get-Sha256 $manifestTarget
}
