[CmdletBinding()]
param(
    [ValidateSet('Start','Run','Status')] [string] $Action = 'Start',
    [string] $StateRoot = 'D:\PathLabData\EvidenceMentor\state',
    [string] $RepositoryRoot = ''
)

$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$datasetId='tumorquantai-breast-ihc-4case-v1'
$campaignId='tumorquantai-ihc-descriptive-execution-20260823-v1'
$taskName='PathLabTumorQuantIhcFixtureCampaign'
$state=[IO.Path]::GetFullPath($StateRoot)
$repository=if([string]::IsNullOrWhiteSpace($RepositoryRoot)){Split-Path -Parent $PSScriptRoot}else{[IO.Path]::GetFullPath($RepositoryRoot)}
$root=Join-Path $state "acquisition\$campaignId"
$statusPath=Join-Path $root 'status.json'
$acquisitionStatusPath=Join-Path $state "acquisition\$datasetId\status.json"
$sourceRoot=Join-Path $state "sources\$datasetId"
$outputRoot=Join-Path $state "derived\qualification-prepared\$datasetId"
$campaignRoot=Join-Path $state "acceptance\campaigns\$campaignId"

function Sha256([string]$Path){(Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()}
function Write-JsonAtomic([string]$Path,[object]$Value){New-Item -ItemType Directory -Path (Split-Path -Parent $Path) -Force|Out-Null;[IO.File]::WriteAllText("$Path.partial",(($Value|ConvertTo-Json -Depth 20)+"`n"),[Text.UTF8Encoding]::new($false));Move-Item "$Path.partial" $Path -Force}
function Write-Status([string]$StateValue,[long]$Completed,[string]$Detail){Write-JsonAtomic $statusPath ([ordered]@{schema='pathlab.acquisition-status/1';datasetId=$campaignId;state=$StateValue;completedBytes=$Completed;totalBytes=1;detail=$Detail;networkContext='interactive-user-acquisition-only';analysisNetwork='disabled';updatedAt=[DateTimeOffset]::UtcNow.ToString('o')})}
function Finish-Task{Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue}

if($Action-eq'Status'){if(Test-Path $statusPath){Get-Content $statusPath -Raw}else{'{"schema":"pathlab.acquisition-status/1","state":"not_started"}'};return}
New-Item -ItemType Directory -Path $root -Force|Out-Null
if($Action-eq'Start'){
    $durable=Join-Path $root 'stage-tumorquantai-ihc-fixture-campaign.ps1';Copy-Item $PSCommandPath $durable -Force
    $taskAction=New-ScheduledTaskAction -Execute 'powershell.exe' -Argument "-NoProfile -ExecutionPolicy Bypass -File `"$durable`" -Action Run -StateRoot `"$state`" -RepositoryRoot `"$repository`""
    $trigger=New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) -RepetitionInterval (New-TimeSpan -Minutes 5) -RepetitionDuration (New-TimeSpan -Days 7)
    $principal=New-ScheduledTaskPrincipal -UserId ([Security.Principal.WindowsIdentity]::GetCurrent().Name) -LogonType Interactive -RunLevel Limited
    $settings=New-ScheduledTaskSettingsSet -StartWhenAvailable -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Hours 2)
    Register-ScheduledTask -TaskName $taskName -Action $taskAction -Trigger $trigger -Principal $principal -Settings $settings -Description 'Build and submit bounded TumorQuantAI IHC fixtures after acquisition.' -Force|Out-Null
    Write-Status 'validating' 0 'Waiting for the checksum-verified IHC source subset.';Start-ScheduledTask $taskName;Get-Content $statusPath -Raw;return
}

try{
    if(-not(Test-Path $acquisitionStatusPath)){Write-Status 'validating' 0 'Waiting for IHC acquisition status.';return}
    $acquisition=Get-Content $acquisitionStatusPath -Raw|ConvertFrom-Json
    if($acquisition.state-ne'completed'){Write-Status 'validating' 0 "Waiting for IHC acquisition: $($acquisition.state).";return}
    Write-Status 'validating' 0 'Building checksum-bound marker fixtures.'
    $endpoint=Get-Content (Join-Path $state 'endpoint.json') -Raw|ConvertFrom-Json
    $token=[IO.File]::ReadAllText((Join-Path $state 'ipc-token')).Trim();$headers=@{Authorization="Bearer $token"}
    Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$($endpoint.port)/v1/control/pause" -Headers $headers|Out-Null
    $samples=[Collections.Generic.List[object]]::new()
    try{
        $runner=Invoke-RestMethod -Headers $headers -Uri "http://127.0.0.1:$($endpoint.port)/v1/status"
        if([int]$runner.queue.active-ne 0){throw 'Runner still has active jobs.'}
        if([long]$runner.quota.derived.usedBytes+3GB-gt[long]$runner.quota.derived.limitBytes){throw 'Derived quota cannot reserve bounded IHC fixtures.'}
        $derivedRoot=Join-Path $state 'derived';$acl=Get-Acl $derivedRoot;$sddl=$acl.Sddl;$identity=[Security.Principal.WindowsIdentity]::GetCurrent().Name
        try{
            $rule=New-Object Security.AccessControl.FileSystemAccessRule($identity,'Modify','ContainerInherit,ObjectInherit','None','Allow');$acl.AddAccessRule($rule)|Out-Null;Set-Acl $derivedRoot $acl
            if(-not(Test-Path $outputRoot)){
                $partial="$outputRoot.partial";Remove-Item $partial -Recurse -Force -ErrorAction SilentlyContinue;New-Item -ItemType Directory -Path $partial -Force|Out-Null
                $ledger=@(Get-Content (Join-Path $sourceRoot 'sample-ledger.jsonl')|ForEach-Object{$_|ConvertFrom-Json})
                if($ledger.Count-ne 4-or@($ledger.patientGroup|Sort-Object -Unique).Count-ne 4){throw 'IHC source ledger is not four-case-disjoint.'}
                $patches=Import-Csv (Join-Path $sourceRoot 'patch_manifest.csv');Add-Type -AssemblyName System.IO.Compression.FileSystem;Add-Type -AssemblyName System.Drawing
                $markers=@('ER','PR','Ki-67','HER2');$caseIndex=0
                foreach($sourceRecord in $ledger){
                    $caseIndex++;$archivePath=Join-Path $sourceRoot "$($sourceRecord.sampleId).zip"
                    if(-not(Test-Path $archivePath)-or(Sha256 $archivePath)-ne$sourceRecord.sha256){throw "IHC archive checksum changed: $($sourceRecord.sampleId)"}
                    $archive=[IO.Compression.ZipFile]::OpenRead($archivePath)
                    try{foreach($marker in $markers){
                        $patch=@($patches|Where-Object{$_.case_alias-eq$sourceRecord.sampleId-and$_.marker-eq$marker}|Sort-Object patch_alias|Select-Object -First 1)
                        if($patch.Count-ne 1-or$patch[0].sha256-notmatch'^[a-f0-9]{64}$'){throw "IHC patch manifest is incomplete: $($sourceRecord.sampleId)/$marker"}
                        $entry=@($archive.Entries|Where-Object FullName -eq $patch[0].public_path)
                        if($entry.Count-ne 1-or[long]$entry[0].Length-ne[long]$patch[0].size_bytes){throw "IHC archive member changed: $($patch[0].patch_alias)"}
                        $sampleId="$($sourceRecord.sampleId)-$($marker.ToLowerInvariant().Replace('-',''))";$sampleRoot=Join-Path $partial $sampleId;New-Item -ItemType Directory -Path $sampleRoot -Force|Out-Null
                        $tiff=Join-Path $sampleRoot 'source.tif';[IO.Compression.ZipFileExtensions]::ExtractToFile($entry[0],$tiff,$true)
                        if((Sha256 $tiff)-ne$patch[0].sha256){throw "IHC patch checksum changed: $($patch[0].patch_alias)"}
                        $bitmap=[Drawing.Bitmap]::new($tiff);try{$side=[Math]::Min($bitmap.Width,$bitmap.Height);$x=[int](($bitmap.Width-$side)/2);$y=[int](($bitmap.Height-$side)/2);$preview=[Drawing.Bitmap]::new(512,512);try{$graphics=[Drawing.Graphics]::FromImage($preview);try{$graphics.InterpolationMode=[Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic;$graphics.DrawImage($bitmap,[Drawing.Rectangle]::new(0,0,512,512),[Drawing.Rectangle]::new($x,$y,$side,$side),[Drawing.GraphicsUnit]::Pixel)}finally{$graphics.Dispose()};$png=Join-Path $sampleRoot 'preview.png';$preview.Save("$png.partial",[Drawing.Imaging.ImageFormat]::Png);Move-Item "$png.partial" $png}finally{$preview.Dispose()}}finally{$bitmap.Dispose();Remove-Item $tiff -Force}
                        $samples.Add([ordered]@{id=$sampleId;caseId=[string]$sourceRecord.sampleId;marker=$marker.ToLowerInvariant();split=if($caseIndex-le 2){'reference'}else{'query'};path=$png;sha256=Sha256 $png;sourceArchiveSha256=[string]$sourceRecord.sha256;sourcePatchSha256=[string]$patch[0].sha256;license='CC-BY-4.0';permittedUse='private-research-descriptive-only';crossSectionCellCorrespondence=$false})
                    }}finally{$archive.Dispose()}
                }
                Write-JsonAtomic (Join-Path $partial 'fixture-manifest.json') ([ordered]@{schema='pathlab.ihc-fixture-set/1';datasetId=$datasetId;researchOnly=$true;notDiagnostic=$true;samples=$samples.ToArray()})
                Move-Item $partial $outputRoot
            }else{$fixture=Get-Content (Join-Path $outputRoot 'fixture-manifest.json') -Raw|ConvertFrom-Json;$fixture.samples|ForEach-Object{$samples.Add($_)}}
            $fixtureSha=Sha256 (Join-Path $outputRoot 'fixture-manifest.json')
        }finally{$restored=New-Object Security.AccessControl.DirectorySecurity;$restored.SetSecurityDescriptorSddlForm($sddl);Set-Acl $derivedRoot $restored}
    }finally{Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$($endpoint.port)/v1/control/resume" -Headers $headers|Out-Null}

    New-Item -ItemType Directory -Path $campaignRoot -Force|Out-Null
    $packSource=Join-Path $repository 'src\main\resources\evidence-packs\ihc-descriptive-v2.json';$packTarget=Join-Path $campaignRoot 'ihc-descriptive-v2.json';Copy-Item $packSource $packTarget -Force
    $protocol=Join-Path $repository 'qualification-protocols\ihc-descriptive-v2.json';$protocolSha=Sha256 $protocol
    if($protocolSha-ne'9b2d3299eed5504fe7d89839cacae6ea5232762d6405bdfab261ad78e30b991f'){throw 'Frozen IHC protocol checksum changed.'}
    $campaignPath=Join-Path $campaignRoot 'campaign.json';$tracks=[Collections.Generic.List[object]]::new();$ledgerLines=[Collections.Generic.List[string]]::new()
    foreach($sample in $samples){$ledgerLines.Add(([ordered]@{sampleId=$sample.id;source="Zenodo-21797920/$($sample.caseId)";patientGroup=$sample.caseId;slideGroup=$sample.id;sha256=$sample.sha256;license='CC-BY-4.0';permittedUse='private-research-descriptive-only';task='ihc-descriptive-integration';split=$sample.split}|ConvertTo-Json -Compress))}
    foreach($marker in @('er','pr','ki-67','her2')){$sample=@($samples|Where-Object{$_.marker-eq$marker-and$_.split-eq'query'}|Select-Object -First 1)[0];$requestName="request-$($marker.Replace('-','')).json";Write-JsonAtomic (Join-Path $campaignRoot $requestName) ([ordered]@{schema='pathlab.evidence-job/2';sourcePath=$sample.path;sourceSha256=$sample.sha256;slideRevision="tumorquantai:$($sample.caseId):$($sample.sourcePatchSha256)";previewPath=$sample.path;sourceWidth=512;sourceHeight=512;packManifest=$packTarget;stain='ihc_dab';marker=$marker;markerIdentitySource='import-metadata';controlsValidated=$false;qualificationCampaignManifest=$campaignPath});$tracks.Add([ordered]@{id="ihc-$($marker.Replace('-',''))-execution-v1";candidateId='ihc-descriptive-v2';capability='ihc-descriptive';scope='deployment';requestPath=$requestName;remediationRequestPath=$null;expectedAttestationPath="attestation-$($marker.Replace('-','')).json";protocolSha256=$protocolSha;dependsOn=@();required=$true})}
    Write-JsonAtomic $campaignPath ([ordered]@{schema='pathlab.qualification-campaign/1';campaignId=$campaignId;createdAt=[DateTimeOffset]::UtcNow.ToString('o');researchOnly=$true;notDiagnostic=$true;maxRemediationAttempts=1;quota=[ordered]@{sourceBytes=45GB;derivedBytes=25GB;modelBytes=10GB;evidenceBytes=10GB;reserveBytes=10GB};tracks=$tracks.ToArray()})
    [IO.File]::WriteAllLines((Join-Path $campaignRoot 'sample-ledger.jsonl'),$ledgerLines,[Text.UTF8Encoding]::new($false));Write-JsonAtomic (Join-Path $campaignRoot 'preparation.json') ([ordered]@{schema='pathlab.campaign-preparation/1';campaignManifest='campaign.json';sampleLedger='sample-ledger.jsonl';artifacts=@()})
    & (Join-Path $repository 'scripts\prepare-all-rounder-campaign.ps1') -PreparationManifest (Join-Path $campaignRoot 'preparation.json') -StateRoot $state
    try{$response=Invoke-RestMethod -Headers $headers -Uri "http://127.0.0.1:$($endpoint.port)/v1/qualification-runs/$campaignId"}catch{if($_.Exception.Response.StatusCode-ne 404){throw};$response=Invoke-RestMethod -Method Post -Headers $headers -ContentType 'application/json' -Body (@{manifestPath=$campaignPath}|ConvertTo-Json -Compress) -Uri "http://127.0.0.1:$($endpoint.port)/v1/qualification-runs"}
    Write-Status 'completed' 1 "Fixture SHA256 $fixtureSha; campaign state $($response.state).";Finish-Task
}catch{Write-Status 'failed' 0 $_.Exception.Message;Finish-Task;throw}finally{Remove-Variable token,headers -ErrorAction SilentlyContinue}
