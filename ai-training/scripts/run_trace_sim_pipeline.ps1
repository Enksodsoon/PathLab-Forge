param(
    [string]$Python = "python",
    [ValidateSet("smoke", "integration")][string]$Tier = "smoke",
    [string]$ArtifactRoot = "$env:LOCALAPPDATA\PathLab Forge\adapt\trace-sim"
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$env:PYTHONPATH = Join-Path $projectRoot "src"

$dataset = (& $Python -m pathlab_adapt.cli generate-synthetic-suite --tier $Tier --artifact-root $ArtifactRoot --shard-events 64000) | ConvertFrom-Json
$manifest = Join-Path $dataset.path "manifest.json"
$seeds = @(20260802, 20260803, 20260804)
foreach ($seed in $seeds) {
    $run = Join-Path $ArtifactRoot "runs\student-3m-seed-$seed"
    & $Python -m pathlab_adapt.cli train-trace-sim --dataset-manifest $manifest --output-dir $run --configuration student-3m --seed $seed --epochs 6 --patience 2 --max-windows 16000 --max-events 128000
}

$winner = Join-Path $ArtifactRoot "runs\student-3m-seed-20260802"
& $Python -m pathlab_adapt.cli export-onnx --checkpoint (Join-Path $winner "best.pt") --output (Join-Path $winner "trace-sim.int8.onnx") --metadata-output (Join-Path $winner "trace-sim.int8.onnx.metadata.json") --configuration student-3m --sample-context 32
& $Python -m pathlab_adapt.cli validate-trace-sim --dataset-manifest $manifest --checkpoint (Join-Path $winner "best.pt") --onnx (Join-Path $winner "trace-sim.int8.onnx") --output (Join-Path $winner "activation-manifest.json") --configuration student-3m

