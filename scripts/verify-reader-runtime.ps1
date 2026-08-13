param([Parameter(Mandatory = $true)][string]$RuntimeRoot)
$ErrorActionPreference = 'Stop'
$lock = Get-Content (Join-Path $PSScriptRoot '..\reader-runtime.lock.properties') |
    Where-Object { $_ -match '^[^#].*=' } |
    ForEach-Object { $parts = $_ -split '=', 2; @{ Key = $parts[0]; Value = $parts[1] } }
$values = @{}
$lock | ForEach-Object { $values[$_.Key] = $_.Value }
if ($values['redistribution.status'] -ne 'APPROVED') {
    throw 'Reader redistribution review is incomplete; production installers are blocked'
}
$bioFormats = Join-Path $RuntimeRoot 'bftools\bioformats_package.jar'
$vips = Join-Path $RuntimeRoot ($(if ($IsWindows) { 'vips\bin\vips.exe' } else { 'vips/bin/vips' }))
if (-not (Test-Path -LiteralPath $bioFormats -PathType Leaf) -or
    -not (Test-Path -LiteralPath $vips -PathType Leaf)) {
    throw 'Reviewed reader runtime is incomplete for this packaging platform'
}
if ((Get-FileHash -LiteralPath $bioFormats -Algorithm SHA256).Hash.ToLowerInvariant() -ne
    $values['bioformats.sha256']) { throw 'Bio-Formats runtime hash does not match the reviewed lock' }
if ((Get-FileHash -LiteralPath $vips -Algorithm SHA256).Hash.ToLowerInvariant() -ne
    $values['libvips.sha256']) { throw 'libvips runtime hash does not match the reviewed lock' }
Write-Host 'Reviewed reader runtime bundle verified.'
