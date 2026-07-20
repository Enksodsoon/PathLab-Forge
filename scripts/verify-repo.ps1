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

$forbidden = Get-ChildItem -Recurse -File | Where-Object {
    $_.Extension -in @(".vsi", ".ets", ".svs", ".ndpi", ".mrxs", ".scn", ".czi", ".oir", ".dzi", ".plslide") -or
    $_.Name -match "\.ome\.tiff?$"
}

if ($forbidden) {
    $forbidden | ForEach-Object { Write-Error "Forbidden slide/generated file: $($_.FullName)" }
    throw "Repository contains forbidden slide/generated files."
}

Write-Host "Repository policy check passed."
