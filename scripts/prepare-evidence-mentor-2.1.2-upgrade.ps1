[CmdletBinding()]
param(
    [string] $JavaHome = '',
    [string] $OutputPath = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repository = Split-Path -Parent $PSScriptRoot
if (-not $JavaHome) {
    $candidate = Get-ChildItem -LiteralPath (Join-Path $env:USERPROFILE '.gradle\jdks') -Filter 'java.exe' -File -Recurse `
        -ErrorAction SilentlyContinue | Where-Object { $_.FullName -match '17' } | Select-Object -First 1
    if ($null -eq $candidate) { throw 'Provide -JavaHome for a Java 17 runtime.' }
    $JavaHome = Split-Path -Parent (Split-Path -Parent $candidate.FullName)
}
if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe') -PathType Leaf)) {
    throw 'JavaHome must contain bin\java.exe.'
}

& (Join-Path $repository 'gradlew.bat') clean test installDist
if ($LASTEXITCODE -ne 0) { throw 'The 2.1.2 distribution did not pass its build and tests.' }

if (-not $OutputPath) { $OutputPath = Join-Path $repository 'build\Upgrade-PathLab-EvidenceMentor-2.1.2.ps1' }
$distribution = Join-Path $repository 'build\install\pathlab-forge'
$installer = Join-Path $repository 'scripts\evidence-mentor-service.ps1'
$content = @"
`$ErrorActionPreference = 'Stop'
& '$($installer.Replace("'", "''"))' -Action Upgrade -Version '2.1.2' ```
  -DistributionPath '$($distribution.Replace("'", "''"))' ```
  -JavaHome '$($JavaHome.Replace("'", "''"))'
"@
$parent = Split-Path -Parent $OutputPath
New-Item -ItemType Directory -Path $parent -Force | Out-Null
[IO.File]::WriteAllText($OutputPath, $content, [Text.UTF8Encoding]::new($false))
Write-Host "Elevated upgrade launcher created: $OutputPath"
Write-Host 'Run it once from an Administrator PowerShell. No reboot is requested.'
