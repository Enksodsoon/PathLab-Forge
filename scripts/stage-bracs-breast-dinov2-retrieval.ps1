[CmdletBinding()]
param(
    [string] $StateRoot = 'D:\PathLabData\EvidenceMentor\state',
    [string] $CampaignId = 'dinov2-bracs-breast-retrieval-20260824-v1',
    [string] $CohortId = 'bracs-roi-breast-retrieval-20x2-v1',
    [string] $ProtocolPath = 'docs\evidence\he-retrieval-qualification-protocol-v1.md'
)

$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$repository=Split-Path -Parent $PSScriptRoot
$state=[IO.Path]::GetFullPath($StateRoot)
$cohortRoot=Join-Path $state "derived\qualification-prepared\$CohortId"
$cohortPath=Join-Path $cohortRoot 'cohort.json'
$modelRoot=Join-Path $state 'models\he-dinov2-small-v1\1'
$packSource=Join-Path $repository 'src\main\resources\evidence-packs\he-dinov2-small-v1.json'
$workerSource=Join-Path $repository 'src\main\resources\model-workers\dinov2-worker.py'
$packTarget=Join-Path $modelRoot 'qualification-pack.json'
$workerTarget=Join-Path $modelRoot 'worker.py'
$campaignRoot=Join-Path $state "acceptance\campaigns\$CampaignId"

function Sha256([string]$Path){(Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()}
function Write-JsonAtomic([string]$Path,[object]$Value){
    [IO.File]::WriteAllText("$Path.partial",(($Value|ConvertTo-Json -Depth 20)+"`n"),[Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath "$Path.partial" -Destination $Path -Force
}

foreach($required in @($packSource,$workerSource,$workerTarget,(Join-Path $modelRoot 'worker.exe'),
        (Join-Path $modelRoot 'model.safetensors'),(Join-Path $modelRoot 'runtime-manifest.json'),
        (Join-Path $state 'endpoint.json'),(Join-Path $state 'ipc-token'))){
    if(-not(Test-Path -LiteralPath $required -PathType Leaf)){throw "Required DINOv2 input is unavailable: $required"}
}
$endpoint=Get-Content -LiteralPath (Join-Path $state 'endpoint.json') -Raw|ConvertFrom-Json
if([version]$endpoint.serviceVersion -lt [version]'2.1.8'){throw 'Service 2.1.8 is required for terminal progress integrity.'}
$token=[IO.File]::ReadAllText((Join-Path $state 'ipc-token')).Trim()
$headers=@{Authorization="Bearer $token"};$origin="http://127.0.0.1:$($endpoint.port)"
$control=Invoke-RestMethod -Method Post -Uri "$origin/v1/control/pause" -Headers $headers
$status=Invoke-RestMethod -Method Get -Uri "$origin/v1/status" -Headers $headers
if($status.queue.active -ne 0){throw 'A worker is active; BRACS staging stopped safely.'}

$derivedRoot=Join-Path $state 'derived';$derivedAcl=Get-Acl -LiteralPath $derivedRoot;$derivedSddl=$derivedAcl.Sddl
try{
    $identity=[Security.Principal.WindowsIdentity]::GetCurrent().Name
    $read=New-Object Security.AccessControl.FileSystemAccessRule($identity,'ReadAndExecute','ContainerInherit,ObjectInherit','None','Allow')
    $derivedAcl.AddAccessRule($read)|Out-Null;Set-Acl -LiteralPath $derivedRoot -AclObject $derivedAcl
    if(-not(Test-Path -LiteralPath $cohortPath -PathType Leaf)){throw 'The BRACS breast cohort is unavailable.'}
    $cohortSha=Sha256 $cohortPath;$cohort=Get-Content -LiteralPath $cohortPath -Raw|ConvertFrom-Json
    if($cohort.schema -ne 'pathlab.qualification-cohort/1' -or @($cohort.samples).Count -ne 80 -or
            @($cohort.samples|Where-Object split -eq 'reference').Count -ne 40 -or
            @($cohort.samples|Where-Object split -eq 'query').Count -ne 40 -or
            @($cohort.samples|Where-Object evaluationGroup -eq 'breast').Count -ne 40 -or
            @($cohort.samples|Where-Object evaluationGroup -eq 'benign-reactive').Count -ne 40 -or
            $cohort.qualificationStatus -ne 'not_evaluable' -or
            'SOURCE_HELD_OUT_NOT_MET_SINGLE_BRACS_RELEASE' -notin $cohort.qualificationReasons){
        throw 'The frozen BRACS breast cohort contract changed.'
    }
    $first=$cohort.samples[0];$tileManifestPath=Join-Path $cohortRoot ([string]$first.tileCacheManifest)
    $tileManifest=Get-Content -LiteralPath $tileManifestPath -Raw|ConvertFrom-Json
    $sourcePath=Join-Path (Split-Path -Parent $tileManifestPath) ([string]$tileManifest.tiles[0].path)
    if((Sha256 $tileManifestPath) -ne [string]$first.tileCacheManifestSha256 -or
            (Sha256 $sourcePath) -ne [string]$tileManifest.tiles[0].sha256){throw 'The BRACS seed tile changed.'}
}finally{
    $restored=New-Object Security.AccessControl.DirectorySecurity;$restored.SetSecurityDescriptorSddlForm($derivedSddl)
    Set-Acl -LiteralPath $derivedRoot -AclObject $restored
}

try{
    $pack=Get-Content -LiteralPath $packSource -Raw|ConvertFrom-Json
    if($pack.packId -ne 'he-dinov2-small-v1' -or $pack.runtimeCompatibility.workerProtocol -ne 'pathlab.model-worker/2' -or
            $pack.rights.allowedUse -ne 'private-research'){
        throw 'The DINOv2 pack contract changed.'
    }
    if((Sha256 $workerSource) -ne [string](@($pack.artifacts|Where-Object name -eq 'workerSource')[0].sha256)){
        throw 'The DINOv2 worker source does not match its artifact ledger.'
    }
    $archiveRoot=Join-Path $modelRoot 'worker-source-archive';New-Item -ItemType Directory -Path $archiveRoot -Force|Out-Null
    $oldWorkerSha=Sha256 $workerTarget;$archivePath=Join-Path $archiveRoot "$oldWorkerSha.py"
    if(-not(Test-Path -LiteralPath $archivePath -PathType Leaf)){
        Copy-Item -LiteralPath $workerTarget -Destination "$archivePath.partial";Move-Item "$archivePath.partial" $archivePath
    }
    Copy-Item -LiteralPath $workerSource -Destination "$workerTarget.partial" -Force;Move-Item "$workerTarget.partial" $workerTarget -Force
    Copy-Item -LiteralPath $packSource -Destination "$packTarget.partial" -Force;Move-Item "$packTarget.partial" $packTarget -Force

    New-Item -ItemType Directory -Path $campaignRoot -Force|Out-Null
    $campaignPath=Join-Path $campaignRoot 'campaign.json';$requestPath=Join-Path $campaignRoot 'request-he-dinov2-small-v1.json'
    $attestationPath=Join-Path $campaignRoot 'attestation-he-dinov2-small-v1.json'
    $protocol=if([IO.Path]::IsPathRooted($ProtocolPath)){$ProtocolPath}else{Join-Path $repository $ProtocolPath}
    if(-not(Test-Path -LiteralPath $protocol -PathType Leaf)){throw 'The frozen H&E protocol is unavailable.'}
    $protocolSha=Sha256 $protocol
    Write-JsonAtomic $requestPath ([ordered]@{
        schema='pathlab.evidence-job/2';sourcePath=$sourcePath;sourceSha256=[string]$tileManifest.source.sha256
        slideRevision=[string]$tileManifest.source.slideRevision;previewPath=$sourcePath
        sourceWidth=[int]$tileManifest.source.width;sourceHeight=[int]$tileManifest.source.height
        packManifest=$packTarget;stain='he';marker="cohort-$cohortSha"
        tileCacheManifest=$tileManifestPath;tileCacheManifestSha256=[string]$first.tileCacheManifestSha256
        qualificationCampaignManifest=$campaignPath;qualificationCohortManifest=$cohortPath
        qualificationCohortManifestSha256=$cohortSha
    })
    Write-JsonAtomic $campaignPath ([ordered]@{
        schema='pathlab.qualification-campaign/1';campaignId=$CampaignId;createdAt=[DateTimeOffset]::UtcNow.ToString('o')
        researchOnly=$true;notDiagnostic=$true;maxRemediationAttempts=1
        quota=[ordered]@{sourceBytes=45GB;derivedBytes=25GB;modelBytes=10GB;evidenceBytes=10GB;reserveBytes=10GB}
        tracks=@([ordered]@{id='he-dinov2-small-bracs-breast-v1';candidateId='he-dinov2-small-v1'
            capability='he-evidence';scope='deployment';requestPath=[IO.Path]::GetFileName($requestPath)
            remediationRequestPath=$null;expectedAttestationPath=[IO.Path]::GetFileName($attestationPath)
            protocolSha256=$protocolSha;dependsOn=@();required=$true})
    })
    [IO.File]::WriteAllLines((Join-Path $campaignRoot 'sample-ledger.jsonl'),@(([ordered]@{
        sampleId=$CohortId;source='BRACS ROI reviewed local release';patientGroup='patient-disjoint-val-test'
        slideGroup='checksum-bound-rois';sha256=$cohortSha;license='CC-BY-NC-4.0';permittedUse='private-research'
        task='he-breast-retrieval-qualification';split='reference-and-query'
    }|ConvertTo-Json -Compress)),[Text.UTF8Encoding]::new($false))
    $preparation=Join-Path $campaignRoot 'preparation.json'
    Write-JsonAtomic $preparation ([ordered]@{schema='pathlab.campaign-preparation/1';campaignManifest='campaign.json';sampleLedger='sample-ledger.jsonl';artifacts=@()})
    & (Join-Path $repository 'scripts\prepare-all-rounder-campaign.ps1') -PreparationManifest $preparation -StateRoot $state
    try{$response=Invoke-RestMethod -Method Get -Uri "$origin/v1/qualification-runs/$CampaignId" -Headers $headers}
    catch{if($_.Exception.Response.StatusCode -ne 404){throw};$response=Invoke-RestMethod -Method Post -Uri "$origin/v1/qualification-runs" -Headers $headers -ContentType 'application/json' -Body (@{manifestPath=$campaignPath}|ConvertTo-Json -Compress)}
    $control=Invoke-RestMethod -Method Post -Uri "$origin/v1/control/resume" -Headers $headers
}finally{
    if($control.acceptingJobs -ne $true){try{$control=Invoke-RestMethod -Method Post -Uri "$origin/v1/control/resume" -Headers $headers}catch{}}
    Remove-Variable token,headers -ErrorAction SilentlyContinue
}
[pscustomobject]@{CampaignId=$response.id;State=$response.state;campaignCompleted=$response.campaignCompleted
    campaignTargetMet=$response.campaignTargetMet;AcceptingJobs=$control.acceptingJobs;SampleCount=80
    CohortManifestSha256=$cohortSha;PermittedUse='private-research-restricted'}
