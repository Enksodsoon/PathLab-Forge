param([switch]$OpenBrowser)

$ErrorActionPreference = 'Stop'
$forgeRoot = Split-Path -Parent $PSScriptRoot
$viewerRoot = 'C:\Users\enkso\.codex\worktrees\pathlab-adapt\viewer'
$runtimeRoot = 'C:\Users\enkso\.cache\codex-runtimes\codex-primary-runtime\dependencies'
$modelRoot = Join-Path $env:LOCALAPPDATA 'PathLab Forge\adapt\trace-sim'
$selectedRunRoot = Join-Path $modelRoot 'runs\student-3m-seed-20260802'
$viewerModelRoot = Join-Path $viewerRoot 'var\research'
$forgeDataRoot = 'C:\Users\enkso\.codex\pathlab-adapt-local-forge'

function Test-Listening([int]$Port) {
    return [bool](Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
}

foreach ($required in @(
    (Join-Path $selectedRunRoot 'trace-sim.int8.onnx'),
    (Join-Path $selectedRunRoot 'activation-manifest.json'),
    (Join-Path $viewerModelRoot 'trace-sim.onnx'),
    (Join-Path $viewerModelRoot 'trace-sim.manifest.json'),
    (Join-Path $viewerRoot '.venv\Scripts\pathlab-api.exe'),
    (Join-Path $runtimeRoot 'bin\fallback\pnpm.cmd')
)) {
    if (-not (Test-Path -LiteralPath $required)) {
        throw "TRACE-SIM prerequisite is missing: $required"
    }
}

$env:PATH = (Join-Path $runtimeRoot 'node\bin') + ';' + $env:PATH
$env:PATHLAB_RESEARCH_ENABLED = 'true'
$env:PATHLAB_RESEARCH_TRACE_SIM_ENABLED = 'true'
$env:PATHLAB_RESEARCH_TRACE_SIM_ARTIFACT = Join-Path $viewerModelRoot 'trace-sim.onnx'
$env:PATHLAB_RESEARCH_TRACE_SIM_MANIFEST = Join-Path $viewerModelRoot 'trace-sim.manifest.json'
$env:PATHLAB_SECURE_COOKIES = 'false'

if (-not (Test-Listening 8000)) {
    Start-Process -FilePath (Join-Path $viewerRoot '.venv\Scripts\pathlab-api.exe') `
        -WorkingDirectory $viewerRoot -WindowStyle Hidden
}
if (-not (Test-Listening 5173)) {
    Start-Process -FilePath (Join-Path $runtimeRoot 'bin\fallback\pnpm.cmd') `
        -ArgumentList '--dir', 'apps/web', 'dev', '--', '--host', '127.0.0.1' `
        -WorkingDirectory $viewerRoot -WindowStyle Hidden
}
if (-not (Test-Listening 51310)) {
    New-Item -ItemType Directory -Force -Path $forgeDataRoot | Out-Null
    Start-Process -FilePath 'powershell.exe' `
        -ArgumentList '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $PSScriptRoot 'run-trace-sim-forge.ps1') `
        -WorkingDirectory $forgeRoot -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $forgeDataRoot 'trace-sim-forge.out.log') `
        -RedirectStandardError (Join-Path $forgeDataRoot 'trace-sim-forge.err.log')
}

$deadline = (Get-Date).AddSeconds(45)
do {
    if ((Test-Listening 8000) -and (Test-Listening 5173) -and (Test-Listening 51310)) { break }
    Start-Sleep -Milliseconds 500
} while ((Get-Date) -lt $deadline)

if (-not ((Test-Listening 8000) -and (Test-Listening 5173) -and (Test-Listening 51310))) {
    throw 'PathLab TRACE-SIM did not become ready within 45 seconds.'
}

Write-Host 'PathLab TRACE-SIM is ready:' -ForegroundColor Green
Write-Host '  Forge:   http://127.0.0.1:51310/app'
Write-Host '  Faculty: http://127.0.0.1:5173/admin/research'
Write-Host '  Learner: http://127.0.0.1:5173/study'

if ($OpenBrowser) {
    $forgeOpenUrl = 'http://127.0.0.1:51310/app'
    $forgeLog = Join-Path $forgeDataRoot 'trace-sim-forge.out.log'
    if (Test-Path -LiteralPath $forgeLog) {
        $authorizationLine = Get-Content -LiteralPath $forgeLog | Select-String -Pattern 'Authorize a new browser once with: (http://\S+)' | Select-Object -Last 1
        if ($authorizationLine -and $authorizationLine.Matches.Count -gt 0) {
            $forgeOpenUrl = $authorizationLine.Matches[0].Groups[1].Value
        }
    }
    Start-Process $forgeOpenUrl
    Start-Process 'http://127.0.0.1:5173/admin/research'
    Start-Process 'http://127.0.0.1:5173/study'
}
