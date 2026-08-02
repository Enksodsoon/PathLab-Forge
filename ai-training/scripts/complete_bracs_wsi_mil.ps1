param(
    [Parameter(Mandatory = $true)][string]$Cli,
    [Parameter(Mandatory = $true)][string]$DevelopmentInventory,
    [Parameter(Mandatory = $true)][string]$FullInventory,
    [Parameter(Mandatory = $true)][string]$FinalInventory,
    [Parameter(Mandatory = $true)][string]$BagsRoot,
    [Parameter(Mandatory = $true)][string]$TemporaryRoot,
    [Parameter(Mandatory = $true)][string]$OutputRoot,
    [Parameter(Mandatory = $true)][string]$RealSlide,
    [Parameter(Mandatory = $true)][string]$Worktree
)

$ErrorActionPreference = "Stop"
Set-Location -LiteralPath $Worktree

$selectedPath = Join-Path $OutputRoot "selected_model.json"
$gridPath = Join-Path $OutputRoot "validation_grid.json"
while (-not ((Test-Path -LiteralPath $selectedPath) -and (Test-Path -LiteralPath $gridPath))) {
    Write-Output ((Get-Date -Format o) + " waiting for validation selection")
    Start-Sleep -Seconds 15
}

$selected = Get-Content -LiteralPath $selectedPath -Raw | ConvertFrom-Json
$modelRoot = [string]$selected.candidate_path
if (-not (Test-Path -LiteralPath $modelRoot -PathType Container)) {
    throw "selected model directory does not exist: $modelRoot"
}

$realInference = Join-Path $OutputRoot "real_unannotated_slide_prediction.json"
Write-Output ((Get-Date -Format o) + " running real unannotated-slide inference")
& $Cli predict-slide-mil `
    --model-root $modelRoot `
    --slide $RealSlide `
    --output $realInference `
    --batch-size 8
if ($LASTEXITCODE -ne 0) {
    throw "real unannotated-slide inference failed with exit code $LASTEXITCODE"
}

if (-not [bool]$selected.advance_to_locked_test) {
    Write-Output ((Get-Date -Format o) + " advancement gates failed; locked test remains untouched")
    exit 0
}

Write-Output ((Get-Date -Format o) + " advancement gates passed; building frozen final inventory")
& $Cli build-bracs-final-inventory `
    --development-inventory $DevelopmentInventory `
    --full-inventory $FullInventory `
    --output $FinalInventory
if ($LASTEXITCODE -ne 0) {
    throw "final inventory construction failed with exit code $LASTEXITCODE"
}

Write-Output ((Get-Date -Format o) + " extracting locked test bags")
& $Cli extract-bracs-wsi-bags `
    --inventory $FinalInventory `
    --bags-root $BagsRoot `
    --temporary-root $TemporaryRoot `
    --splits test `
    --max-tiles 128 `
    --target-mpp 0.5 `
    --batch-size 8 `
    --threads 6
if ($LASTEXITCODE -ne 0) {
    throw "locked test feature extraction failed with exit code $LASTEXITCODE"
}

Write-Output ((Get-Date -Format o) + " verifying exact frozen development and test cohort")
& $Cli verify-bracs-wsi-bags --inventory $FinalInventory --bags-root $BagsRoot
if ($LASTEXITCODE -ne 0) {
    throw "final cohort verification failed with exit code $LASTEXITCODE"
}

$testResult = Join-Path $modelRoot "final_test_evaluation.json"
if (Test-Path -LiteralPath $testResult -PathType Leaf) {
    Write-Output ((Get-Date -Format o) + " one-time locked test result already exists; refusing to repeat")
    exit 0
}

Write-Output ((Get-Date -Format o) + " running one-time locked test evaluation")
& $Cli evaluate-bracs-mil-test `
    --bags-root $BagsRoot `
    --model-root $modelRoot `
    --bootstrap-iterations 500 `
    --seed 20260802
if ($LASTEXITCODE -ne 0) {
    throw "locked test evaluation failed with exit code $LASTEXITCODE"
}

Write-Output ((Get-Date -Format o) + " BRACS WSI MIL completion pipeline finished")
