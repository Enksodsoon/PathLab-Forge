param([Parameter(Mandatory = $true)][string]$RuntimeRoot)
$ErrorActionPreference = 'Stop'
$gradle = if ($env:OS -eq 'Windows_NT') { 'gradlew.bat' } else { 'gradlew' }
& (Join-Path $PSScriptRoot "../$gradle") -p (Join-Path $PSScriptRoot '..') verifyReaderRuntimeBundle "-Ppathlab.forge.readerRuntimeRoot=$RuntimeRoot"
if ($LASTEXITCODE -ne 0) { throw 'Portable reader runtime verification failed' }
