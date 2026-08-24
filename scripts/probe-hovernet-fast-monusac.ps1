[CmdletBinding()]
param([string] $StateRoot = 'D:\PathLabData\EvidenceMentor\state')

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$state = [IO.Path]::GetFullPath($StateRoot)
$repository = Split-Path -Parent $PSScriptRoot
& (Join-Path $PSScriptRoot 'build-hovernet-fast-monusac-pack.ps1') -StateRoot $state
$packRoot = Join-Path $state 'models\cell-hovernet-fast-monusac-v1\6'
$runtimeRoot = Join-Path $state 'models\he-dinov2-small-v1\1'
$python = Join-Path $runtimeRoot 'runtime\Scripts\python.exe'
$worker = Join-Path $packRoot 'worker.py'
$probeId = 'hovernet-runtime-probe-' + [DateTimeOffset]::UtcNow.ToString('yyyyMMdd-HHmmss')
$probeRoot = Join-Path $state "acquisition\hovernet-fast-monusac-v1\runtime-probes\$probeId"
$imagePath = Join-Path $probeRoot 'synthetic-he-runtime-fixture.png'
$requestPath = Join-Path $probeRoot 'request.json'
$partialResult = Join-Path $probeRoot 'result.json.partial'
$resultPath = Join-Path $probeRoot 'result.json'

function Sha256([string] $Path) {
    (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}
function Write-JsonAtomic([string] $Path, [object] $Value) {
    [IO.File]::WriteAllText("$Path.partial", (($Value | ConvertTo-Json -Depth 20) + "`n"),
        [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath "$Path.partial" -Destination $Path -Force
}

foreach ($required in @($python,$worker,(Join-Path $packRoot 'runtime-reference.json'))) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Required HoVer-Net probe input is unavailable: $required" }
}
$gpuName = (& nvidia-smi.exe --query-gpu=name --format=csv,noheader 2>$null | Select-Object -First 1).Trim()
if ($gpuName -notin @('NVIDIA Quadro P2000','Quadro P2000')) {
    throw "NVIDIA Quadro P2000 is required for this runtime probe; found '$gpuName'."
}

New-Item -ItemType Directory -Path $probeRoot -Force | Out-Null
Add-Type -AssemblyName System.Drawing
$bitmap = New-Object Drawing.Bitmap 256,256
$graphics = [Drawing.Graphics]::FromImage($bitmap)
try {
    $graphics.Clear([Drawing.Color]::FromArgb(246,225,232))
    $pink = New-Object Drawing.SolidBrush ([Drawing.Color]::FromArgb(222,147,178))
    $purple = New-Object Drawing.SolidBrush ([Drawing.Color]::FromArgb(78,42,119))
    try {
        $graphics.FillRectangle($pink, 8, 8, 240, 240)
        for ($row = 0; $row -lt 8; $row++) {
            for ($column = 0; $column -lt 9; $column++) {
                $x = 18 + $column * 25 + (($row % 2) * 7)
                $y = 20 + $row * 27
                $graphics.FillEllipse($purple, $x, $y, 10 + (($row + $column) % 4), 14)
            }
        }
    } finally { $pink.Dispose(); $purple.Dispose() }
    $bitmap.Save($imagePath, [Drawing.Imaging.ImageFormat]::Png)
} finally { $graphics.Dispose(); $bitmap.Dispose() }

Write-JsonAtomic $requestPath ([ordered]@{
    schema='pathlab.hovernet-runtime-probe/1';scope='runtime-probe-only-not-qualification'
    imagePath=$imagePath;imageSha256=(Sha256 $imagePath);syntheticFixture=$true
    expectedHost='NVIDIA Quadro P2000';analysisNetwork='disabled'
})

$prior = [ordered]@{
    PATHLAB_ANALYSIS_NETWORK=$env:PATHLAB_ANALYSIS_NETWORK;HF_HUB_OFFLINE=$env:HF_HUB_OFFLINE
    TRANSFORMERS_OFFLINE=$env:TRANSFORMERS_OFFLINE;PATHLAB_MAX_VRAM_MIB=$env:PATHLAB_MAX_VRAM_MIB
    PATHLAB_MAX_RAM_MIB=$env:PATHLAB_MAX_RAM_MIB;CUBLAS_WORKSPACE_CONFIG=$env:CUBLAS_WORKSPACE_CONFIG
}
try {
    $env:PATHLAB_ANALYSIS_NETWORK = 'disabled'
    $env:HF_HUB_OFFLINE = '1'
    $env:TRANSFORMERS_OFFLINE = '1'
    $env:PATHLAB_MAX_VRAM_MIB = '4608'
    $env:PATHLAB_MAX_RAM_MIB = '16384'
    $env:CUBLAS_WORKSPACE_CONFIG = ':4096:8'
    & $python $worker --request $requestPath --output $partialResult --offline
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $partialResult -PathType Leaf)) {
        throw 'HoVer-Net offline worker probe failed.'
    }
} finally {
    foreach ($name in $prior.Keys) {
        if ($null -eq $prior[$name]) { Remove-Item "Env:$name" -ErrorAction SilentlyContinue }
        else { Set-Item "Env:$name" $prior[$name] }
    }
}
$result = Get-Content -LiteralPath $partialResult -Raw | ConvertFrom-Json
if ($result.schema -ne 'pathlab.hovernet-runtime-probe-result/1' -or $result.status -ne 'completed' -or
        $result.scope -ne 'runtime-probe-only-not-qualification' -or $result.activationEligible -ne $false -or
        $result.runtime.device -notin @('NVIDIA Quadro P2000','Quadro P2000') -or $result.runtime.architecture -ne 'sm_61' -or
        $result.runtime.cuda -ne '12.6' -or $result.runtime.analysisNetwork -ne 'disabled' -or
        [double]$result.runtime.peakVramMiB -gt 4608 -or [double]$result.runtime.peakRamMiB -gt 16384) {
    throw 'HoVer-Net runtime probe result failed its fixed acceptance contract.'
}
Move-Item -LiteralPath $partialResult -Destination $resultPath
[pscustomobject]@{
    ProbeId=$probeId;Status=$result.status;Scope=$result.scope;InstanceCount=$result.instanceCount
    Device=$result.runtime.device;Cuda=$result.runtime.cuda;PeakVramMiB=$result.runtime.peakVramMiB
    PeakRamMiB=$result.runtime.peakRamMiB;ElapsedSeconds=$result.runtime.elapsedSeconds
    ResultPath=$resultPath;ResultSha256=(Sha256 $resultPath)
}
