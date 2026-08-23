[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $Script,
    [Parameter(Mandatory = $true)]
    [string] $Log
)

$ErrorActionPreference = 'Stop'
try {
    & ([IO.Path]::GetFullPath($Script)) *>&1 |
        Out-File -LiteralPath ([IO.Path]::GetFullPath($Log)) -Encoding utf8 -Force
    exit 0
} catch {
    ($_ | Out-String) | Out-File -LiteralPath ([IO.Path]::GetFullPath($Log)) -Encoding utf8 -Force
    exit 1
}
