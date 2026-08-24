[CmdletBinding(SupportsShouldProcess)]
param(
    [string] $StateRoot = 'D:\PathLabData\EvidenceMentor\state',
    [string] $BasePython = 'C:\Users\enkso\AppData\Local\Programs\Python\Python312'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$modelRoot = [IO.Path]::GetFullPath((Join-Path $StateRoot 'models\he-dinov2-small-v1\1'))
$runtimeRoot = Join-Path $modelRoot 'runtime'
$portableBase = Join-Path $runtimeRoot 'base'
$partialBase = "$portableBase.partial-$PID"
$runtimeManifest = Join-Path $modelRoot 'runtime-manifest.json'
$pyvenv = Join-Path $runtimeRoot 'pyvenv.cfg'

if (-not $modelRoot.StartsWith(
        [IO.Path]::GetFullPath((Join-Path $StateRoot 'models')) + [IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Portable runtime target escaped the protected model root.'
}
foreach ($required in @('python.exe','python3.dll','python312.dll','vcruntime140.dll','Lib','DLLs')) {
    if (-not (Test-Path -LiteralPath (Join-Path $BasePython $required))) {
        throw "Python 3.12 base runtime is incomplete: $required"
    }
}
if (-not (Test-Path -LiteralPath $runtimeManifest -PathType Leaf) -or
        -not (Test-Path -LiteralPath $pyvenv -PathType Leaf)) {
    throw 'Installed DINOv2 runtime metadata is incomplete.'
}
if (Test-Path -LiteralPath $portableBase) {
    throw "Portable runtime already exists and will not be overwritten: $portableBase"
}

function Get-Sha256([string] $Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Write-AtomicUtf8([string] $Path, [string] $Value) {
    $partial = "$Path.partial"
    [IO.File]::WriteAllText($partial, $Value, [Text.UTF8Encoding]::new($false))
    $backup = "$Path.backup-$([Guid]::NewGuid().ToString('N'))"
    try {
        [IO.File]::Replace($partial, $Path, $backup)
    } finally {
        if (Test-Path -LiteralPath $backup) { Remove-Item -LiteralPath $backup -Force }
    }
}

if ($PSCmdlet.ShouldProcess($portableBase, 'Stage a checksum-ledgered portable Python 3.12 base runtime')) {
    New-Item -ItemType Directory -Path $partialBase -Force | Out-Null
    try {
        foreach ($name in @('python.exe','pythonw.exe','python3.dll','python312.dll',
                'vcruntime140.dll','vcruntime140_1.dll','LICENSE.txt')) {
            $source = Join-Path $BasePython $name
            if (Test-Path -LiteralPath $source -PathType Leaf) {
                Copy-Item -LiteralPath $source -Destination $partialBase
            }
        }
        Copy-Item -LiteralPath (Join-Path $BasePython 'DLLs') -Destination $partialBase -Recurse
        $targetLib = Join-Path $partialBase 'Lib'
        New-Item -ItemType Directory -Path $targetLib -Force | Out-Null
        Get-ChildItem -LiteralPath (Join-Path $BasePython 'Lib') -Force |
            Where-Object Name -notin @('site-packages','__pycache__') |
            Copy-Item -Destination $targetLib -Recurse

        Move-Item -LiteralPath $partialBase -Destination $portableBase

        $pyvenvText = @"
home = $portableBase
include-system-site-packages = false
version = 3.12.10
executable = $portableBase\python.exe
command = portable PathLab Evidence Mentor runtime
"@
        $pyvenvPartial = "$pyvenv.portable-$PID"
        [IO.File]::WriteAllText($pyvenvPartial, $pyvenvText, [Text.UTF8Encoding]::new($false))

        $manifest = Get-Content -LiteralPath $runtimeManifest -Raw | ConvertFrom-Json
        if ($manifest.schema -ne 'pathlab.model-runtime/1' -or $manifest.python -ne '3.12.10') {
            throw 'Installed runtime manifest is not the pinned Python 3.12 runtime.'
        }
        $portableEntries = [Collections.Generic.List[object]]::new()
        Get-ChildItem -LiteralPath $portableBase -File -Recurse | Sort-Object FullName | ForEach-Object {
            $relative = $_.FullName.Substring($modelRoot.Length + 1).Replace('\','/')
            $portableEntries.Add([ordered]@{ path = $relative; bytes = $_.Length; sha256 = Get-Sha256 $_.FullName })
        }
        $portableEntries.Add([ordered]@{
            path = 'runtime/pyvenv.cfg'
            bytes = (Get-Item -LiteralPath $pyvenvPartial).Length
            sha256 = Get-Sha256 $pyvenvPartial
        })
        $retained = @($manifest.files | Where-Object {
            $_.path -ne 'runtime/pyvenv.cfg' -and -not ([string]$_.path).StartsWith('runtime/base/')
        })
        $manifest.files = @($retained + $portableEntries | Sort-Object path)
        $manifest | Add-Member -NotePropertyName portableBase -NotePropertyValue 'runtime/base' -Force
        $manifestText = ($manifest | ConvertTo-Json -Depth 8) + "`n"

        Write-AtomicUtf8 $pyvenv $pyvenvText
        Write-AtomicUtf8 $runtimeManifest $manifestText
    } finally {
        if (Test-Path -LiteralPath $partialBase) {
            $resolved = [IO.Path]::GetFullPath($partialBase)
            if (-not $resolved.StartsWith($runtimeRoot + [IO.Path]::DirectorySeparatorChar,
                    [StringComparison]::OrdinalIgnoreCase)) {
                throw 'Refusing to clean a path outside the installed runtime.'
            }
            Remove-Item -LiteralPath $resolved -Recurse -Force
        }
    }
}

[pscustomobject]@{
    RuntimeRoot = $runtimeRoot
    PortableBase = $portableBase
    PortableFiles = @(Get-ChildItem -LiteralPath $portableBase -File -Recurse).Count
    RuntimeManifestSha256 = Get-Sha256 $runtimeManifest
    PyvenvSha256 = Get-Sha256 $pyvenv
}
