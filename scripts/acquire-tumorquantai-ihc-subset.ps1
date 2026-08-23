[CmdletBinding()]
param(
    [ValidateSet('Start','Run','Status')] [string] $Action = 'Start',
    [string] $StateRoot = 'D:\PathLabData\EvidenceMentor\state'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$recordId = '21797920'
$datasetId = 'tumorquantai-breast-ihc-4case-v1'
$taskName = 'PathLabTumorQuantIhcAcquisition'
$sourceLimit = 45GB
$expected = @(
    [ordered]@{case='TQA_BC_ZPVYVY4T27UKEAYPKAOX';name='TQA_BC_ZPVYVY4T27UKEAYPKAOX.zip';bytes=80105716L;md5='6d63faf7ba43d598bece904c7deafbcd';sha256='772c193f2e71d6afed5630d5f74a3fd73ac892070587aacea8a39106baa9be14'},
    [ordered]@{case='TQA_BC_KHLKIB6TVKGYE7SUWAOX';name='TQA_BC_KHLKIB6TVKGYE7SUWAOX.zip';bytes=85446094L;md5='003df2c43571fe3dfc9b853ff061376f';sha256='c92ad0567931f7079d5271da58db998fa22e954a4157614262a84fc44108b997'},
    [ordered]@{case='TQA_BC_2R5PE76UT27ESW6WXFR7';name='TQA_BC_2R5PE76UT27ESW6WXFR7.zip';bytes=85446098L;md5='4ab4ad3d13b003f8527560497288a292';sha256='a56bd9a978b48ea5906e773f72d795ed37b326da1177776bfd9ee37e8b4fb12e'},
    [ordered]@{case='TQA_BC_5QIEJCI66QT6FMUHJ67O';name='TQA_BC_5QIEJCI66QT6FMUHJ67O.zip';bytes=170892174L;md5='66ffcff753b501f98f46b885c8a6d8bc';sha256='35105f2b5d13edd4cae94e26fa309eb1a9e094402e8df8a8d68aa5a6232d4412'}
)
$requiredBytes = 0L
foreach ($item in $expected) { $requiredBytes += [long]$item.bytes }
$state = [IO.Path]::GetFullPath($StateRoot)
$preparationRoot = Join-Path $state 'acquisition\tumorquantai-breast-ihc-manifest-v1'
$root = Join-Path $state "acquisition\$datasetId"
$downloadRoot = Join-Path $root 'downloads'
$sourceRoot = Join-Path $state "sources\$datasetId"
$statusPath = Join-Path $root 'status.json'
$recordPath = Join-Path $root 'zenodo-record.json'
$ledgerPath = Join-Path $sourceRoot 'sample-ledger.jsonl'
$reservationPath = Join-Path $state "quota\reservations\source\$datasetId.reservation"

function Write-JsonAtomic([string] $Path, [object] $Value) {
    New-Item -ItemType Directory -Path (Split-Path -Parent $Path) -Force | Out-Null
    [IO.File]::WriteAllText("$Path.partial", (($Value | ConvertTo-Json -Depth 20) + "`n"),
        [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath "$Path.partial" -Destination $Path -Force
}
function Write-Status([string] $StateValue, [long] $Completed, [string] $Detail) {
    Write-JsonAtomic $statusPath ([ordered]@{
        schema='pathlab.acquisition-status/1';datasetId=$datasetId;state=$StateValue
        completedBytes=$Completed;totalBytes=$requiredBytes;detail=$Detail
        networkContext='interactive-user-acquisition-only';analysisNetwork='disabled'
        updatedAt=[DateTimeOffset]::UtcNow.ToString('o')
    })
}
function Directory-Bytes([string] $Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Container)) { return 0L }
    $sum = Get-ChildItem -LiteralPath $Path -File -Recurse | Measure-Object Length -Sum
    if ($null -eq $sum -or $null -eq $sum.Sum) { return 0L }; return [long]$sum.Sum
}
function Get-DownloadedBytes {
    $total=0L
    foreach($item in $expected) {
        $found = $false
        foreach($path in @((Join-Path $downloadRoot $item.name),(Join-Path $downloadRoot "$($item.name).partial"))) {
            if(Test-Path -LiteralPath $path -PathType Leaf) {
                $total += [Math]::Min([long](Get-Item $path).Length,[long]$item.bytes)
                $found = $true
                break
            }
        }
        if (-not $found) {
            $chunkRoot = Join-Path $downloadRoot ".chunks\$($item.name)"
            $total += [Math]::Min((Directory-Bytes $chunkRoot), [long]$item.bytes)
        }
    }
    return $total
}
function Finish-Task { Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue }

if ($Action -eq 'Status') {
    if(Test-Path -LiteralPath $statusPath -PathType Leaf){Get-Content $statusPath -Raw}else{'{"schema":"pathlab.acquisition-status/1","state":"not_started"}'}
    return
}
New-Item -ItemType Directory -Path $downloadRoot -Force | Out-Null
$reservationRoot = Split-Path -Parent $reservationPath
if (-not (Test-Path -LiteralPath $reservationRoot -PathType Container)) {
    throw 'The service-managed source quota reservation root is unavailable.'
}

if ($Action -eq 'Start') {
    $review = Get-Content (Join-Path $preparationRoot 'rights-and-integrity.json') -Raw | ConvertFrom-Json
    if($review.datasetLicense -ne 'CC-BY-4.0' -or $review.qualificationStatus -ne 'not_evaluable') {
        throw 'Pinned TumorQuantAI rights review is unavailable or unexpectedly promotes qualification.'
    }
    $manifest = Import-Csv (Join-Path $preparationRoot 'manifest\archive_manifest.csv')
    foreach($item in $expected) {
        $row=@($manifest|Where-Object archive_filename -eq $item.name)
        if($row.Count -ne 1 -or [long]$row[0].archive_size_bytes -ne $item.bytes -or
                $row[0].md5 -ne $item.md5 -or $row[0].sha256 -ne $item.sha256) {
            throw "Frozen TumorQuantAI archive manifest changed: $($item.name)"
        }
    }
    $used=Directory-Bytes (Join-Path $state 'sources');$reserved=0L
    Get-ChildItem (Split-Path -Parent $reservationPath) -Filter '*.reservation' -File -ErrorAction SilentlyContinue |
        Where-Object FullName -ne $reservationPath | ForEach-Object {$reserved += [long]([IO.File]::ReadAllText($_.FullName).Trim())}
    if($requiredBytes -gt $sourceLimit-$used-$reserved){throw 'The 45 GB source quota cannot reserve the TumorQuantAI subset.'}
    if(-not(Test-Path $reservationPath)){[IO.File]::WriteAllText("$reservationPath.partial",[string]$requiredBytes);Move-Item "$reservationPath.partial" $reservationPath}
    $record=Invoke-RestMethod -Uri "https://zenodo.org/api/records/$recordId" -TimeoutSec 60
    if([string]$record.id -ne $recordId -or $record.metadata.doi -ne '10.5281/zenodo.21797920' -or $record.metadata.license.id -ne 'cc-by-4.0'){throw 'TumorQuantAI record identity or rights changed.'}
    foreach($item in $expected){$file=@($record.files|Where-Object key -eq $item.name);if($file.Count-ne 1-or[long]$file[0].size-ne$item.bytes-or$file[0].checksum-ne"md5:$($item.md5)"){throw "Zenodo metadata changed: $($item.name)"}}
    Write-JsonAtomic $recordPath $record
    $durable=Join-Path $root 'acquire-tumorquantai-ihc-subset.ps1';Copy-Item $PSCommandPath $durable -Force
    $taskAction=New-ScheduledTaskAction -Execute 'powershell.exe' -Argument "-NoProfile -ExecutionPolicy Bypass -File `"$durable`" -Action Run -StateRoot `"$state`""
    $trigger=New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1)
    $principal=New-ScheduledTaskPrincipal -UserId ([Security.Principal.WindowsIdentity]::GetCurrent().Name) -LogonType Interactive -RunLevel Limited
    $settings=New-ScheduledTaskSettingsSet -StartWhenAvailable -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Days 7)
    Register-ScheduledTask -TaskName $taskName -Action $taskAction -Trigger $trigger -Principal $principal -Settings $settings -Description 'Acquire bounded TumorQuantAI descriptive IHC fixtures.' -Force|Out-Null
    Write-Status 'queued' 0 'Checksum-bound four-case download scheduled.';Start-ScheduledTask $taskName;Get-Content $statusPath -Raw;return
}

$curl=(Get-Command curl.exe -ErrorAction Stop).Source
$chunkBytes = 8MB
try {
    foreach($item in $expected){
        $target=Join-Path $downloadRoot $item.name
        if(Test-Path $target){if((Get-Item $target).Length-eq$item.bytes-and(Get-FileHash $target -Algorithm SHA256).Hash.ToLowerInvariant()-eq$item.sha256){continue};throw "Existing file mismatch: $($item.name)"}
        $partial="$target.partial";Remove-Item $partial -Force -ErrorAction SilentlyContinue
        $chunkRoot=Join-Path $downloadRoot ".chunks\$($item.name)"
        New-Item -ItemType Directory -Path $chunkRoot -Force|Out-Null
        $url="https://zenodo.org/api/records/$recordId/files/$([Uri]::EscapeDataString($item.name))/content"
        $chunkIndex=0
        for($offset=0L;$offset-lt[long]$item.bytes;$offset+=$chunkBytes){
            $end=[Math]::Min([long]$item.bytes-1,$offset+$chunkBytes-1)
            $expectedChunkBytes=$end-$offset+1
            $chunk=Join-Path $chunkRoot ('{0:D5}.part' -f $chunkIndex)
            if(Test-Path $chunk){if((Get-Item $chunk).Length-eq$expectedChunkBytes){$chunkIndex++;continue};Remove-Item $chunk -Force}
            $exitCode=-1
            for ($attempt = 1; $attempt -le 3; $attempt++) {
                Write-Status 'transferring' (Get-DownloadedBytes) "Downloading $($item.name) chunk $($chunkIndex+1) (attempt $attempt of 3)."
                $process=Start-Process $curl -ArgumentList @('--fail','--location','--silent','--show-error','--remove-on-error','--range',"$offset-$end",'--output',('"'+$chunk+'.partial"'),$url) -PassThru -NoNewWindow
                while(-not $process.HasExited){Write-Status 'transferring' (Get-DownloadedBytes) "Downloading $($item.name) chunk $($chunkIndex+1) (attempt $attempt of 3).";Start-Sleep 15;$process.Refresh()}
                $process.WaitForExit();$exitCode=$process.ExitCode
                if((Test-Path "$chunk.partial")-and(Get-Item "$chunk.partial").Length-eq$expectedChunkBytes){Move-Item "$chunk.partial" $chunk;break}
                Remove-Item "$chunk.partial" -Force -ErrorAction SilentlyContinue
                if($attempt-lt 3){Start-Sleep -Seconds $(if($attempt-eq 1){5}else{30})}
            }
            if(-not(Test-Path $chunk)-or(Get-Item $chunk).Length-ne$expectedChunkBytes){throw "TumorQuantAI chunk transfer failed: $($item.name) bytes $offset-$end"}
            $chunkIndex++
        }
        $output=[IO.File]::Open($partial,[IO.FileMode]::Create,[IO.FileAccess]::Write,[IO.FileShare]::None)
        try {Get-ChildItem $chunkRoot -Filter '*.part' -File|Sort-Object Name|ForEach-Object{$input=[IO.File]::OpenRead($_.FullName);try{$input.CopyTo($output)}finally{$input.Dispose()}}} finally {$output.Dispose()}
        if((Get-Item $partial).Length-ne$item.bytes-or(Get-FileHash $partial -Algorithm SHA256).Hash.ToLowerInvariant()-ne$item.sha256){throw "TumorQuantAI assembled checksum mismatch: $($item.name)"}
        Move-Item $partial $target
        Remove-Item $chunkRoot -Recurse -Force
    }
} catch {
    Write-Status 'failed' (Get-DownloadedBytes) $_.Exception.Message
    Finish-Task
    throw
}

Write-Status 'validating' $requiredBytes 'Publishing checksum and case-disjoint source ledger.'
New-Item -ItemType Directory -Path $sourceRoot -Force|Out-Null
$lines=[Collections.Generic.List[string]]::new()
foreach($item in $expected){$from=Join-Path $downloadRoot $item.name;$to=Join-Path $sourceRoot $item.name;Move-Item $from $to -Force;$lines.Add(([ordered]@{
    sampleId=$item.case;source='Zenodo record 21797920';patientGroup = $item.case;slideGroup=$item.case
    sha256=$item.sha256;upstreamSha256=$item.sha256;upstreamMd5=$item.md5;bytes=$item.bytes
    license='CC-BY-4.0';permittedUse='private-research-descriptive-only';task='ihc-descriptive-integration';split='case-disjoint-fixture'
}|ConvertTo-Json -Compress))}
[IO.File]::WriteAllLines($ledgerPath,$lines,[Text.UTF8Encoding]::new($false))
Copy-Item (Join-Path $preparationRoot 'manifest\patch_manifest.csv') (Join-Path $sourceRoot 'patch_manifest.csv') -Force
Copy-Item $recordPath (Join-Path $sourceRoot 'zenodo-record.json') -Force
Remove-Item $reservationPath -Force
Write-Status 'completed' $requiredBytes 'Four case-disjoint archives are checksum-verified; scientific IHC qualification remains not_evaluable.'
Finish-Task
