param(
    [Parameter(Mandatory = $true)][string]$Cli,
    [Parameter(Mandatory = $true)][string]$Python,
    [Parameter(Mandatory = $true)][string]$Inventory,
    [Parameter(Mandatory = $true)][string]$BagsRoot,
    [Parameter(Mandatory = $true)][string]$TemporaryRoot,
    [Parameter(Mandatory = $true)][string]$OutputRoot,
    [Parameter(Mandatory = $true)][string]$Worktree
)

$ErrorActionPreference = "Stop"
Set-Location -LiteralPath $Worktree

while ($true) {
    $workers = @(
        Get-CimInstance Win32_Process |
            Where-Object { $_.CommandLine -like "*extract-bracs-wsi-bags*shard-*" }
    )
    $bags = @(Get-ChildItem -LiteralPath $BagsRoot -Filter *.npz -File -Recurse).Count
    Write-Output ((Get-Date -Format o) + " waiting shard_processes=" + $workers.Count + " bags=" + $bags)
    if ($workers.Count -eq 0) {
        break
    }
    Start-Sleep -Seconds 15
}

$verified = $false
foreach ($attempt in 1..2) {
    Write-Output ((Get-Date -Format o) + " canonical extraction attempt=" + $attempt)
    & $Cli extract-bracs-wsi-bags `
        --inventory $Inventory `
        --bags-root $BagsRoot `
        --temporary-root $TemporaryRoot `
        --splits train validation `
        --max-tiles 128 `
        --target-mpp 0.5 `
        --batch-size 8 `
        --threads 6
    if ($LASTEXITCODE -ne 0) {
        throw "canonical extraction/retry failed with exit code $LASTEXITCODE"
    }
    Write-Output ((Get-Date -Format o) + " verifying exact cohort attempt=" + $attempt)
    & $Cli verify-bracs-wsi-bags --inventory $Inventory --bags-root $BagsRoot
    if ($LASTEXITCODE -eq 0) {
        $verified = $true
        break
    }
}
if (-not $verified) {
    throw "cohort verification failed after one clean retry"
}

Write-Output ((Get-Date -Format o) + " running validation-only model grid")
& $Python ai-training\scripts\run_bracs_mil_validation_grid.py `
    --bags-root $BagsRoot `
    --output-root $OutputRoot `
    --max-epochs 200 `
    --patience 25 `
    --seed 20260802
if ($LASTEXITCODE -ne 0) {
    throw "validation grid failed with exit code $LASTEXITCODE"
}

Write-Output ((Get-Date -Format o) + " validation pipeline complete")
