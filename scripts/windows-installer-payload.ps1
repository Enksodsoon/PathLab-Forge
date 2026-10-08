param([Parameter(Mandatory=$true)][string]$Installer, [Parameter(Mandatory=$true)][string]$Destination,
    [ValidateSet('SQUIRREL','ZIP')][string]$Mode='SQUIRREL')
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression
function Expand-CheckedZip([string]$Archive, [string]$Output) {
    $resolvedOutput=[IO.Path]::GetFullPath($Output)
    [IO.Directory]::CreateDirectory($resolvedOutput) | Out-Null
    $prefix=$resolvedOutput+[IO.Path]::DirectorySeparatorChar
    $seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    $zip=[IO.Compression.ZipFile]::OpenRead($Archive)
    try {
        [long]$total=0
        if($zip.Entries.Count -gt 100000) { throw 'Archive entry limit exceeded' }
        foreach($entry in $zip.Entries) {
            $name=$entry.FullName
            if([string]::IsNullOrEmpty($name) -or $name.StartsWith('/') -or $name.Contains('\') -or $name.Contains(':') -or
                ($name.Split('/') -contains '..') -or ($name.Split('/') -contains '.') -or
                ((($entry.ExternalAttributes -shr 16) -band 0xF000) -eq 0xA000)) { throw 'Unsafe archive entry' }
            $target=[IO.Path]::GetFullPath((Join-Path $resolvedOutput $name))
            if(-not $target.StartsWith($prefix,[StringComparison]::OrdinalIgnoreCase) -or -not $seen.Add($target.TrimEnd('\','/'))) { throw 'Archive path escaped or collided' }
            $total+=$entry.Length
            if($total -gt 8GB) { throw 'Archive size limit exceeded' }
            if($name.EndsWith('/')) { [IO.Directory]::CreateDirectory($target) | Out-Null; continue }
            [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($target)) | Out-Null
            $inputStream=$entry.Open()
            $outputStream=[IO.File]::Open($target,[IO.FileMode]::CreateNew)
            try { $inputStream.CopyTo($outputStream,65536) } finally { $outputStream.Dispose(); $inputStream.Dispose() }
        }
    } finally { $zip.Dispose() }
}
if(Test-Path -LiteralPath $Destination) { throw 'Extraction destination must not exist' }
if($Mode -eq 'ZIP') { Expand-CheckedZip $Installer $Destination; exit }
Add-Type @'
using System;
using System.IO;
using System.Runtime.InteropServices;
public static class ForgeResource {
  [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)] static extern IntPtr LoadLibraryEx(string file, IntPtr reserved, uint flags);
  [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)] static extern IntPtr FindResource(IntPtr module, IntPtr id, string type);
  [DllImport("kernel32.dll", SetLastError=true)] static extern IntPtr LoadResource(IntPtr module, IntPtr resource);
  [DllImport("kernel32.dll", SetLastError=true)] static extern IntPtr LockResource(IntPtr resource);
  [DllImport("kernel32.dll", SetLastError=true)] static extern uint SizeofResource(IntPtr module, IntPtr resource);
  [DllImport("kernel32.dll")] static extern bool FreeLibrary(IntPtr module);
  public static void Export(string source, string output) {
    // DATAFILE loading maps resources only; never invokes installer code.
    IntPtr module=LoadLibraryEx(source,IntPtr.Zero,2);
    if(module==IntPtr.Zero) throw new IOException("Installer resource cannot be mapped");
    try {
      IntPtr resource=FindResource(module,new IntPtr(131),"DATA");
      uint length=SizeofResource(module,resource);
      IntPtr bytes=LockResource(LoadResource(module,resource));
      if(resource==IntPtr.Zero || bytes==IntPtr.Zero || length==0 || length>Int32.MaxValue) throw new IOException("Squirrel DATA/131 payload is missing or too large");
      byte[] buffer=new byte[1048576];
      using(var stream=new FileStream(output,FileMode.CreateNew,FileAccess.Write)) {
        for(int offset=0;offset<(int)length;) { int count=Math.Min(buffer.Length,(int)length-offset); Marshal.Copy(IntPtr.Add(bytes,offset),buffer,0,count); stream.Write(buffer,0,count); offset+=count; }
      }
    } finally { FreeLibrary(module); }
  }
}
'@
$root=[IO.Path]::GetFullPath($Destination)
if(Test-Path -LiteralPath $root) { throw 'Extraction destination must not exist' }
[IO.Directory]::CreateDirectory($root) | Out-Null
$archive=Join-Path $root 'setup-payload.zip'
[ForgeResource]::Export([IO.Path]::GetFullPath($Installer),$archive)
$bootstrap=Join-Path $root 'bootstrap'
Expand-CheckedZip $archive $bootstrap
$packages=@(Get-ChildItem -LiteralPath $bootstrap -File -Filter '*-full.nupkg')
if($packages.Count -ne 1 -or @(Get-ChildItem -LiteralPath $bootstrap -File -Filter '*.nupkg').Count -ne 1) { throw 'Full installer must contain exactly one full package' }
Expand-CheckedZip $packages[0].FullName (Join-Path $root 'package')
if(-not (Test-Path -LiteralPath (Join-Path $root 'package/lib/net45/PathLabForge.exe'))) { throw 'Forge payload is missing' }
