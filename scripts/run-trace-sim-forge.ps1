$ErrorActionPreference = 'Stop'
$forgeRoot = Split-Path -Parent $PSScriptRoot
$forgeDataRoot = 'C:\Users\enkso\.codex\pathlab-adapt-local-forge'
$env:JAVA_TOOL_OPTIONS = '-Dpathlab.forge.port=51310 -Dpathlab.forge.viewerCredentialTarget=PathLabForge-TRACE-SIM-local'
Set-Location -LiteralPath $forgeRoot
& (Join-Path $forgeRoot 'gradlew.bat') run "--args=--serve --no-browser --data-root $forgeDataRoot"
exit $LASTEXITCODE
