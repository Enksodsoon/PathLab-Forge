param(
    [string]$Owner = "Enksodsoon",
    [string]$Repository = "PathLab-Forge"
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path ".git")) {
    git init
}

git branch -M main
git add .
if (-not (git status --porcelain)) {
    Write-Host "No uncommitted files to bootstrap."
} else {
    git commit -m "chore: bootstrap PathLab Forge"
}

if (Get-Command gh -ErrorAction SilentlyContinue) {
    gh repo create "$Owner/$Repository" --private --source . --remote origin --push
    Write-Host "Created and pushed https://github.com/$Owner/$Repository"
} else {
    Write-Host ""
    Write-Host "GitHub CLI was not found."
    Write-Host "Create a private empty repository named $Repository, then run:"
    Write-Host "  git remote add origin https://github.com/$Owner/$Repository.git"
    Write-Host "  git push -u origin main"
}
