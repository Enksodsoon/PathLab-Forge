$ErrorActionPreference = "Stop"

$required = @(
    "README.md",
    "AGENTS.md",
    "CODEX_START_HERE.md",
    "docs/architecture/SYSTEM.md",
    "docs/plans/active/current.md"
)

foreach ($path in $required) {
    if (-not (Test-Path $path)) {
        throw "Missing required repository file: $path"
    }
}

$repositoryFiles = & git ls-files --cached --others --exclude-standard
if ($LASTEXITCODE -ne 0) {
    throw "Could not enumerate repository files with git."
}

$forbidden = $repositoryFiles | Where-Object {
    $extension = [System.IO.Path]::GetExtension($_).ToLowerInvariant()
    $extension -in @(".vsi", ".ets", ".svs", ".ndpi", ".mrxs", ".scn", ".czi", ".oir", ".dzi", ".plslide") -or
    $_ -match "(?i)\.ome\.tiff?$"
}

if ($forbidden) {
    $forbidden | ForEach-Object { Write-Error "Forbidden slide/generated file: $_" }
    throw "Repository contains forbidden slide/generated files."
}

Write-Host "Repository policy check passed."
