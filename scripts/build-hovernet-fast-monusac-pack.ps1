[CmdletBinding()]
param([string] $StateRoot = 'D:\PathLabData\EvidenceMentor\state')

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$packId = 'cell-hovernet-fast-monusac-v1'
$version = '7'
$modelLimit = 10GB
$reservationBytes = 512MB
$candidateId = 'hovernet-fast-monusac-v1'
$candidateVersion = '1'
$codeCommit = '67e2ce5e3f1a64a2ece77ad1c24233653a9e0901'
$codeSha256 = 'a14857014569fc169371f04832fe96e605f755d35151f59901e1ee7176a05914'
$weightName = 'hovernet_fast_monusac_type_tf2pytorch.tar'
$weightSha256 = '5b1c642d9884e20c8fa0b80a6cfef793f483d47eaa5df6183baddc3f57e88a35'
$runtimeManifestSha256 = 'b52c4d80f914c7e6e56d05e594d9478333d763861b5133e3c82af2a6a17fd942'
$scipyName = 'scipy-1.18.1-cp312-cp312-win_amd64.whl'
$scipyBytes = 36658278L
$scipySha256 = '5e4d44984abc0020154ea81b247adeddcc3ac5527b975ff798bd1ba0adc513c2'
$scipyUrl = 'https://files.pythonhosted.org/packages/39/e7/979fd14e75008623df31ba70d6bb144700f68feadcea042021c06a05bf82/scipy-1.18.1-cp312-cp312-win_amd64.whl'
$state = [IO.Path]::GetFullPath($StateRoot)
$modelsRoot = [IO.Path]::GetFullPath((Join-Path $state 'models'))
$candidateRoot = Join-Path $modelsRoot "$candidateId\$candidateVersion"
$runtimeRoot = Join-Path $modelsRoot 'he-dinov2-small-v1\1'
$installRoot = Join-Path $modelsRoot "$packId\$version"
$stagingRoot = Join-Path $modelsRoot ".partial-$packId-$version"
$reservationPath = Join-Path $modelsRoot ".acquisition-reservations\$packId.reservation"
$workerSource = Join-Path (Split-Path -Parent $PSScriptRoot) 'src\main\resources\model-workers\hovernet-worker.py'

function Sha256([string] $Path) {
    (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}
function Directory-Bytes([string] $Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Container)) { return 0L }
    $sum = 0L
    Get-ChildItem -LiteralPath $Path -File -Recurse | ForEach-Object { $sum = [long]($sum + $_.Length) }
    return $sum
}
function Write-JsonAtomic([string] $Path, [object] $Value) {
    [IO.File]::WriteAllText("$Path.partial", (($Value | ConvertTo-Json -Depth 30) + "`n"),
        [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath "$Path.partial" -Destination $Path -Force
}
function File-Ledger([string] $Root) {
    $rootPrefix = [IO.Path]::GetFullPath($Root).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    @(Get-ChildItem -LiteralPath $Root -File -Recurse | Sort-Object FullName | ForEach-Object {
        if (-not $_.FullName.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Model build ledger path escaped its root.'
        }
        [ordered]@{path=$_.FullName.Substring($rootPrefix.Length).Replace('\','/');bytes=$_.Length;sha256=(Sha256 $_.FullName)}
    })
}

if (Test-Path -LiteralPath (Join-Path $installRoot 'build-receipt.json') -PathType Leaf) {
    $receipt = Get-Content -LiteralPath (Join-Path $installRoot 'build-receipt.json') -Raw | ConvertFrom-Json
    if ($receipt.schema -ne 'pathlab.model-pack-build/1' -or $receipt.packId -ne $packId -or
            (Sha256 (Join-Path $installRoot 'worker.py')) -ne [string]$receipt.workerSha256 -or
            (Sha256 (Join-Path $installRoot 'runtime-reference.json')) -ne [string]$receipt.runtimeReferenceSha256) {
        throw 'Existing HoVer-Net executable pack failed its immutable receipt.'
    }
    Write-Output "Already built $packId/$version under $installRoot"
    return
}
if (Test-Path -LiteralPath $installRoot) { throw 'Incomplete HoVer-Net install root requires review.' }

$candidateLedgerPath = Join-Path $candidateRoot 'candidate-ledger.json'
$codeArchive = Join-Path $candidateRoot "hover_net-$codeCommit.zip"
$weightPath = Join-Path $candidateRoot $weightName
$runtimeManifest = Join-Path $runtimeRoot 'runtime-manifest.json'
foreach ($required in @($candidateLedgerPath,$codeArchive,$weightPath,$runtimeManifest,$workerSource)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Required HoVer-Net build input is unavailable: $required" }
}
if ((Sha256 $codeArchive) -ne $codeSha256 -or (Sha256 $weightPath) -ne $weightSha256 -or
        (Sha256 $runtimeManifest) -ne $runtimeManifestSha256) {
    throw 'A frozen HoVer-Net or shared-runtime input checksum changed.'
}
$candidateLedger = Get-Content -LiteralPath $candidateLedgerPath -Raw | ConvertFrom-Json
if ($candidateLedger.candidateId -ne $candidateId -or $candidateLedger.atlasCleanEligible -ne $false -or
        $candidateLedger.qualificationStatus -ne 'not_evaluable') {
    throw 'The acquired HoVer-Net rights or qualification boundary changed.'
}

New-Item -ItemType Directory -Path (Split-Path -Parent $reservationPath) -Force | Out-Null
$used = Directory-Bytes $modelsRoot
$reserved = 0L
Get-ChildItem -LiteralPath (Split-Path -Parent $reservationPath) -Filter '*.reservation' -File -ErrorAction SilentlyContinue |
    Where-Object FullName -ne $reservationPath | ForEach-Object { $reserved += [long]([IO.File]::ReadAllText($_.FullName).Trim()) }
if ($reservationBytes -gt $modelLimit - $used - $reserved) { throw 'The 10 GB model quota cannot reserve the HoVer-Net adapter.' }
[IO.File]::WriteAllText("$reservationPath.partial", [string]$reservationBytes)
Move-Item -LiteralPath "$reservationPath.partial" -Destination $reservationPath -Force

try {
    New-Item -ItemType Directory -Path $stagingRoot -Force | Out-Null
    $dependencies = Join-Path $stagingRoot 'dependencies'
    $overlay = Join-Path $stagingRoot 'overlay'
    $upstream = Join-Path $stagingRoot 'upstream'
    New-Item -ItemType Directory -Path $dependencies,$overlay,$upstream -Force | Out-Null
    $scipyWheel = Join-Path $dependencies $scipyName
    if (Test-Path -LiteralPath $scipyWheel -PathType Leaf) {
        if ((Get-Item -LiteralPath $scipyWheel).Length -ne $scipyBytes -or (Sha256 $scipyWheel) -ne $scipySha256) {
            throw 'Existing pinned SciPy wheel checksum changed.'
        }
    } else {
        Invoke-WebRequest -Uri $scipyUrl -OutFile "$scipyWheel.partial" -Headers @{'User-Agent'='PathLab-Forge/2.1 research acquisition'} -MaximumRedirection 10
        if ((Get-Item -LiteralPath "$scipyWheel.partial").Length -ne $scipyBytes -or
                (Sha256 "$scipyWheel.partial") -ne $scipySha256) { throw 'Pinned SciPy wheel checksum changed.' }
        Move-Item -LiteralPath "$scipyWheel.partial" -Destination $scipyWheel
    }

    & tar.exe -xf $scipyWheel -C $overlay
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath (Join-Path $overlay 'scipy\__init__.py') -PathType Leaf)) {
        throw 'Pinned SciPy overlay extraction failed.'
    }
    $prefix = "hover_net-$codeCommit"
    $sourceEntries = @(
        "$prefix/models/__init__.py", "$prefix/models/hovernet/__init__.py",
        "$prefix/models/hovernet/net_desc.py", "$prefix/models/hovernet/net_utils.py",
        "$prefix/models/hovernet/utils.py"
    )
    & tar.exe -xf $codeArchive -C $upstream --strip-components 1 @sourceEntries
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath (Join-Path $upstream 'models\hovernet\net_desc.py') -PathType Leaf)) {
        throw 'Pinned HoVer-Net source extraction failed.'
    }
    $utilsPath = Join-Path $upstream 'models\hovernet\utils.py'
    $utilsText = [IO.File]::ReadAllText($utilsPath).Replace("from matplotlib import cm`n",'').Replace("from matplotlib import cm`r`n",'')
    [IO.File]::WriteAllText($utilsPath, $utilsText, [Text.UTF8Encoding]::new($false))
    $netUtilsPath = Join-Path $upstream 'models\hovernet\net_utils.py'
    $netUtilsText = [IO.File]::ReadAllText($netUtilsPath).Replace("from config import Config`n",'').Replace("from config import Config`r`n",'')
    [IO.File]::WriteAllText($netUtilsPath, $netUtilsText, [Text.UTF8Encoding]::new($false))
    Copy-Item -LiteralPath $workerSource -Destination (Join-Path $stagingRoot 'worker.py')

    Write-JsonAtomic (Join-Path $stagingRoot 'source-manifest.json') ([ordered]@{
        schema='pathlab.model-source-manifest/1';repository='https://github.com/vqdang/hover_net.git'
        commit=$codeCommit;license='MIT';files=(File-Ledger $upstream)
    })
    Write-JsonAtomic (Join-Path $stagingRoot 'overlay-manifest.json') ([ordered]@{
        schema='pathlab.model-overlay-manifest/1';package='scipy';version='1.18.1'
        wheel=$scipyName;wheelSha256=$scipySha256;license='BSD-3-Clause';files=(File-Ledger $overlay)
    })
    Write-JsonAtomic (Join-Path $stagingRoot 'runtime-reference.json') ([ordered]@{
        schema='pathlab.model-runtime-reference/1';sharedRuntimePack='he-dinov2-small-v1';sharedRuntimeVersion='1'
        sharedRuntimeRoot=$runtimeRoot;sharedRuntimeManifestSha256=$runtimeManifestSha256
        pythonRelativePath='runtime/Scripts/python.exe';candidateId=$candidateId;candidateVersion=$candidateVersion
        candidateRoot=$candidateRoot;candidateLedgerSha256=(Sha256 $candidateLedgerPath)
        weightFile=$weightName;weightSha256=$weightSha256;runtimeCopiedIntoCandidate=$false;analysisNetwork='disabled'
    })
    $workerSha = Sha256 (Join-Path $stagingRoot 'worker.py')
    $sourceManifestSha = Sha256 (Join-Path $stagingRoot 'source-manifest.json')
    $overlayManifestSha = Sha256 (Join-Path $stagingRoot 'overlay-manifest.json')
    $runtimeReferenceSha = Sha256 (Join-Path $stagingRoot 'runtime-reference.json')
    Write-JsonAtomic (Join-Path $stagingRoot 'pack.json') ([ordered]@{
        schema='pathlab.ai-pack/1';packId=$packId;version=$version;capability='cell-morphology';acceptedStains=@('he','ihc_dab')
        preprocessing=[ordered]@{id='hovernet-fast-monusac-v1';tilePixels=512}
        artifacts=@(
            [ordered]@{name='workerSource';sha256=$workerSha;source='repo:src/main/resources/model-workers/hovernet-worker.py'},
            [ordered]@{name='source-manifest';sha256=$sourceManifestSha;source="github:vqdang/hover_net@$codeCommit"},
            [ordered]@{name='overlay-manifest';sha256=$overlayManifestSha;source="pypi:$scipyName"},
            [ordered]@{name='runtime-reference';sha256=$runtimeReferenceSha;source='pathlab-local:he-dinov2-small-v1/1'},
            [ordered]@{name='weights';sha256=$weightSha256;source='official-hovernet:monusac-fast-checkpoint'}
        )
        runtimeCompatibility=[ordered]@{workerProtocol='pathlab.model-worker/2';executionProvider='cuda';cuda='12.6';gpuArchitecture='sm_61';requiresExternalWorker=$true}
        licenseLedger=@(
            [ordered]@{component='HoVer-Net code';license='MIT';revision=$codeCommit;permittedUse='private-research';redistributable=$false;derivativesAllowed=$true},
            [ordered]@{component='MoNuSAC-derived weights';license='CC-BY-NC-SA-4.0';revision=$weightSha256;permittedUse='private-research';redistributable=$false;derivativesAllowed=$false},
            [ordered]@{component='SciPy compatibility overlay';license='BSD-3-Clause';revision='1.18.1';permittedUse='private-research';redistributable=$false;derivativesAllowed=$true}
        )
        rights=[ordered]@{license='Mixed restricted research lineage';allowedUse='benchmark-only';redistributable=$false;derivativesAllowed=$false;reviewedAt=[DateTimeOffset]::UtcNow.ToString('o')}
        resourceEnvelope=[ordered]@{maxRamMiB=16384;maxVramMiB=4608;maxSeconds=1200;network=$false}
        validation=[ordered]@{status='not-evaluable';modelCard='docs/model-cards/cell-hovernet-fast-monusac-v1.md';heldOutEvaluation='not-evaluable-held-out-campaign-pending'}
        outputSchema='pathlab.ai-evidence/1'
    })
    Write-JsonAtomic (Join-Path $stagingRoot 'build-receipt.json') ([ordered]@{
        schema='pathlab.model-pack-build/1';packId=$packId;version=$version;workerSha256=$workerSha
        runtimeReferenceSha256=$runtimeReferenceSha;runtimeCopiedIntoCandidate=$false
        analysisNetwork='disabled';qualificationStatus='not_evaluable';activationEligible=$false
        builtAt=[DateTimeOffset]::UtcNow.ToString('o')
    })
    $actual = Directory-Bytes $stagingRoot
    $other = (Directory-Bytes $modelsRoot) - $actual
    if ($actual -gt $reservationBytes -or $actual -gt $modelLimit - $other) { throw 'HoVer-Net adapter exceeded its model quota reservation.' }
    New-Item -ItemType Directory -Path (Split-Path -Parent $installRoot) -Force | Out-Null
    Move-Item -LiteralPath $stagingRoot -Destination $installRoot
    Remove-Item -LiteralPath $reservationPath -Force
    Write-Output "Built $packId/$version under $installRoot ($actual bytes; shared runtime not copied)."
} catch {
    throw
}
