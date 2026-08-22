[CmdletBinding()]
param(
    [string] $StateRoot = 'D:\PathLabData\EvidenceMentor\state',
    [string] $CampaignId = 'all-rounder-candidate-preflight-20260822-v1'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repository = Split-Path -Parent $PSScriptRoot
$state = [IO.Path]::GetFullPath($StateRoot)
$campaignParent = [IO.Path]::GetFullPath((Join-Path $state 'acceptance\campaigns'))
$root = [IO.Path]::GetFullPath((Join-Path $campaignParent $CampaignId))
if (-not $root.StartsWith($campaignParent + [IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase)) { throw 'Campaign path escaped the protected state root.' }

function Sha256([string] $Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
function WriteJson([string] $Path, [object] $Value) {
    [IO.File]::WriteAllText($Path, (($Value | ConvertTo-Json -Depth 16) + "`n"),
        [Text.UTF8Encoding]::new($false))
}

$protocol = Join-Path $repository 'docs\evidence\candidate-preflight-protocol-v1.md'
$protocolSha = Sha256 $protocol
$specs = @(
    @{ id='he-dinov2-small-v1'; capability='he-evidence'; scope='deployment' },
    @{ id='he-hibou-b-v1'; capability='he-evidence'; scope='deployment' },
    @{ id='he-gigapath-benchmark-v1'; capability='he-evidence'; scope='local-benchmark' },
    @{ id='cell-hovernet-fast-v1'; capability='cell-morphology'; scope='deployment' },
    @{ id='cell-pathosam-benchmark-v1'; capability='cell-morphology'; scope='local-benchmark' },
    @{ id='tutor-qwen3-0.6b-int4-v1'; capability='grounded-tutor'; scope='deployment' },
    @{ id='atlas-he-72m-v1'; capability='atlas-distillation'; scope='deployment' }
)

New-Item -ItemType Directory -Path $root -Force | Out-Null
$campaignPath = Join-Path $root 'campaign.json'
$tracks = [Collections.Generic.List[object]]::new()
foreach ($spec in $specs) {
    $packSource = Join-Path $repository "src\main\resources\evidence-packs\$($spec.id).json"
    $packTarget = Join-Path $root "$($spec.id).json"
    Copy-Item -LiteralPath $packSource -Destination $packTarget -Force
    $requestName = "request-$($spec.id).json"
    WriteJson (Join-Path $root $requestName) ([ordered]@{ packManifest = $packTarget })
    $tracks.Add([ordered]@{
        id = $spec.id; candidateId = $spec.id; capability = $spec.capability; scope = $spec.scope
        requestPath = $requestName; remediationRequestPath = $null; expectedAttestationPath = $null
        protocolSha256 = $protocolSha; dependsOn = @(); required = $true
    })
}
WriteJson $campaignPath ([ordered]@{
    schema = 'pathlab.qualification-campaign/1'; campaignId = $CampaignId
    createdAt = '2026-08-22T00:00:00Z'; researchOnly = $true; notDiagnostic = $true
    maxRemediationAttempts = 1
    quota = [ordered]@{ sourceBytes = 45GB; derivedBytes = 25GB; modelBytes = 10GB; evidenceBytes = 10GB; reserveBytes = 10GB }
    tracks = $tracks
})

$endpoint = Get-Content -LiteralPath (Join-Path $state 'endpoint.json') -Raw | ConvertFrom-Json
if ($endpoint.serviceVersion -ne '2.1.1') { throw "Service 2.1.1 is required; found $($endpoint.serviceVersion)." }
$token = [IO.File]::ReadAllText((Join-Path $state 'ipc-token')).Trim()
try {
    try {
        $response = Invoke-RestMethod -Method Get -Uri "http://127.0.0.1:$($endpoint.port)/v1/qualification-runs/$CampaignId" -Headers @{ Authorization = "Bearer $token" }
    } catch {
        if ($_.Exception.Response.StatusCode -ne 404) { throw }
        $response = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$($endpoint.port)/v1/qualification-runs" `
            -Headers @{ Authorization = "Bearer $token" } -ContentType 'application/json' `
            -Body (@{ manifestPath = $campaignPath } | ConvertTo-Json -Compress)
    }
} finally { Remove-Variable token -ErrorAction SilentlyContinue }

[pscustomobject]@{
    CampaignId = $response.id; State = $response.state
    CampaignCompleted = $response.campaignCompleted; CampaignTargetMet = $response.campaignTargetMet
    ManifestPath = $campaignPath; ManifestSha256 = Sha256 $campaignPath
}
