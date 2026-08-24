[CmdletBinding()]
param(
    [string] $StateRoot = 'D:\PathLabData\EvidenceMentor\state',
    [string] $CampaignId = 'monusac-hovernet-fast-heldout-20260824-v1',
    [string] $CohortId = 'monusac2020-cell-heldout-23-v1'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repository = Split-Path -Parent $PSScriptRoot
$state = [IO.Path]::GetFullPath($StateRoot)
$derivedRoot = Join-Path $state 'derived'
$cohortRoot = Join-Path $derivedRoot "qualification-prepared\$CohortId"
$cohortPath = Join-Path $cohortRoot 'cohort.json'
$packSource = Join-Path $state 'models\cell-hovernet-fast-monusac-v1\6\pack.json'
$protocolPath = Join-Path $repository 'qualification-protocols\cell-hovernet-fast-monusac-v1.json'
$campaignRoot = Join-Path $state "acceptance\campaigns\$CampaignId"
$campaignPath = Join-Path $campaignRoot 'campaign.json'
$requestPath = Join-Path $campaignRoot 'request-cell-hovernet-fast-monusac-v1.json'
$attestationPath = Join-Path $campaignRoot 'attestation-cell-hovernet-fast-monusac-v1.json'

function Sha256([string] $Path) {
    (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}
function Write-JsonAtomic([string] $Path, [object] $Value) {
    New-Item -ItemType Directory -Path (Split-Path -Parent $Path) -Force | Out-Null
    [IO.File]::WriteAllText("$Path.partial", (($Value | ConvertTo-Json -Depth 30) + "`n"),
        [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath "$Path.partial" -Destination $Path -Force
}

foreach ($required in @($packSource,$protocolPath,(Join-Path $state 'endpoint.json'),(Join-Path $state 'ipc-token'))) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Required HoVer-Net qualification input is unavailable: $required"
    }
}
$endpoint = Get-Content -LiteralPath (Join-Path $state 'endpoint.json') -Raw | ConvertFrom-Json
if ([version]$endpoint.serviceVersion -lt [version]'2.1.7') {
    throw 'Service 2.1.7 is required for resumable HoVer-Net held-out qualification.'
}
$token = [IO.File]::ReadAllText((Join-Path $state 'ipc-token')).Trim()
$headers = @{Authorization="Bearer $token"}
$origin = "http://127.0.0.1:$($endpoint.port)"
$control = Invoke-RestMethod -Method Post -Uri "$origin/v1/control/pause" -Headers $headers
$status = Invoke-RestMethod -Method Get -Uri "$origin/v1/status" -Headers $headers
if ($status.queue.active -ne 0) { throw 'A worker is active; HoVer-Net staging stopped safely.' }

$derivedAcl = Get-Acl -LiteralPath $derivedRoot
$derivedSddl = $derivedAcl.Sddl
try {
    $currentIdentity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
    $temporaryRead = New-Object Security.AccessControl.FileSystemAccessRule(
        $currentIdentity, 'ReadAndExecute', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
    $derivedAcl.AddAccessRule($temporaryRead) | Out-Null
    Set-Acl -LiteralPath $derivedRoot -AclObject $derivedAcl
    if (-not (Test-Path -LiteralPath $cohortPath -PathType Leaf)) { throw 'The frozen MoNuSAC cohort is unavailable.' }
    $cohortSha = Sha256 $cohortPath
    $cohort = Get-Content -LiteralPath $cohortPath -Raw | ConvertFrom-Json
    if ($cohort.schema -ne 'pathlab.cell-qualification-cohort/1' -or @($cohort.samples).Count -ne 23 -or
            @($cohort.samples.organ | Sort-Object -Unique).Count -ne 4 -or
            @($cohort.samples | Where-Object patientOverlapWithTraining -eq $true).Count -ne 0 -or
            $cohort.gates.minimumMacroPq -ne 0.45 -or $cohort.gates.minimumInstanceDice -ne 0.70 -or
            $cohort.gates.maximumCountError -ne 0.15 -or $cohort.gates.maximumMorphometryBias -ne 0.10 -or
            $cohort.gates.maximumFailedRegionRate -ne 0.05) {
        throw 'The frozen MoNuSAC cohort or gates changed.'
    }
    $seed = $cohort.samples[0]
    $sourcePath = Join-Path $cohortRoot ([string]$seed.imagePath)
    if ((Sha256 $sourcePath) -ne [string]$seed.imageSha256) { throw 'The MoNuSAC seed image changed.' }
} finally {
    $restoredAcl = New-Object Security.AccessControl.DirectorySecurity
    $restoredAcl.SetSecurityDescriptorSddlForm($derivedSddl)
    Set-Acl -LiteralPath $derivedRoot -AclObject $restoredAcl
}

try {
    New-Item -ItemType Directory -Path $campaignRoot -Force | Out-Null
    $packTarget = Join-Path $campaignRoot 'cell-hovernet-fast-monusac-v1.json'
    Copy-Item -LiteralPath $packSource -Destination "$packTarget.partial" -Force
    Move-Item -LiteralPath "$packTarget.partial" -Destination $packTarget -Force
    $pack = Get-Content -LiteralPath $packTarget -Raw | ConvertFrom-Json
    if ($pack.packId -ne 'cell-hovernet-fast-monusac-v1' -or $pack.version -ne '6' -or
            $pack.runtimeCompatibility.workerProtocol -ne 'pathlab.model-worker/2' -or
            $pack.validation.status -ne 'not-evaluable' -or $pack.rights.allowedUse -ne 'benchmark-only') {
        throw 'The installed HoVer-Net qualification pack changed.'
    }
    $protocolSha = Sha256 $protocolPath
    Write-JsonAtomic $requestPath ([ordered]@{
        schema='pathlab.evidence-job/2';sourcePath=$sourcePath;sourceSha256=[string]$seed.imageSha256
        slideRevision="monusac:$($seed.patientGroup):$($seed.imageSha256)";previewPath=$sourcePath
        sourceWidth=[int]$seed.width;sourceHeight=[int]$seed.height;packManifest=$packTarget
        stain='he';marker='generic';qualificationCampaignManifest=$campaignPath
        qualificationCohortManifest=$cohortPath;qualificationCohortManifestSha256=$cohortSha
    })
    Write-JsonAtomic $campaignPath ([ordered]@{
        schema='pathlab.qualification-campaign/1';campaignId=$CampaignId
        createdAt=[DateTimeOffset]::UtcNow.ToString('o');researchOnly=$true;notDiagnostic=$true
        maxRemediationAttempts=1
        quota=[ordered]@{sourceBytes=45GB;derivedBytes=25GB;modelBytes=10GB;evidenceBytes=10GB;reserveBytes=10GB}
        tracks=@([ordered]@{
            id='cell-hovernet-fast-monusac-v1';candidateId='cell-hovernet-fast-monusac-v1'
            capability='cell-morphology';scope='local-benchmark';requestPath=[IO.Path]::GetFileName($requestPath)
            remediationRequestPath=$null;expectedAttestationPath=[IO.Path]::GetFileName($attestationPath)
            protocolSha256=$protocolSha;dependsOn=@();required=$true
        })
    })
    [IO.File]::WriteAllLines((Join-Path $campaignRoot 'sample-ledger.jsonl'), @(([ordered]@{
        sampleId=$CohortId;source='Official MoNuSAC 2020 testing archive';patientGroup='23-held-out-patients'
        slideGroup='23-annotation-bearing-fields';sha256=$cohortSha;license='CC-BY-NC-SA-4.0'
        permittedUse='private-research-restricted';task='cell-instance-qualification';split='qualification-held-out-test'
    } | ConvertTo-Json -Compress)), [Text.UTF8Encoding]::new($false))
    $preparationPath = Join-Path $campaignRoot 'preparation.json'
    Write-JsonAtomic $preparationPath ([ordered]@{
        schema='pathlab.campaign-preparation/1';campaignManifest='campaign.json'
        sampleLedger='sample-ledger.jsonl';artifacts=@()
    })
    & (Join-Path $repository 'scripts\prepare-all-rounder-campaign.ps1') `
        -PreparationManifest $preparationPath -StateRoot $state
    try {
        $response = Invoke-RestMethod -Method Get -Uri "$origin/v1/qualification-runs/$CampaignId" -Headers $headers
    } catch {
        if ($_.Exception.Response.StatusCode -ne 404) { throw }
        $response = Invoke-RestMethod -Method Post -Uri "$origin/v1/qualification-runs" -Headers $headers `
            -ContentType 'application/json' -Body (@{manifestPath=$campaignPath}|ConvertTo-Json -Compress)
    }
    $control = Invoke-RestMethod -Method Post -Uri "$origin/v1/control/resume" -Headers $headers
} finally {
    if ($control.acceptingJobs -ne $true) {
        try { $control = Invoke-RestMethod -Method Post -Uri "$origin/v1/control/resume" -Headers $headers } catch { }
    }
    Remove-Variable token,headers -ErrorAction SilentlyContinue
}
[pscustomobject]@{
    CampaignId=$response.id;State=$response.state;campaignCompleted=$response.campaignCompleted
    campaignTargetMet=$response.campaignTargetMet;AcceptingJobs=$control.acceptingJobs
    SampleCount=23;CohortManifestSha256=$cohortSha;PackVersion='6';PermittedUse='private-research-restricted'
}
