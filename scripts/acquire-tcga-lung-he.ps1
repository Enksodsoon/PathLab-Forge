[CmdletBinding()]
param(
    [ValidateSet('Start','Run','Status')] [string] $Action = 'Start',
    [string] $StateRoot = 'D:\PathLabData\EvidenceMentor\state'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$datasetId = 'tcga-luad-lusc-he-20x2-v1'
$taskName = 'PathLabTcgaLungAcquisition'
$sourceLimit = 45GB
$state = [IO.Path]::GetFullPath($StateRoot)
$acquisitionRoot = Join-Path $state "acquisition\$datasetId"
$downloadRoot = Join-Path $acquisitionRoot 'downloads'
$sourceRoot = Join-Path $state "sources\$datasetId"
$statusPath = Join-Path $acquisitionRoot 'status.json'
$manifestPath = Join-Path $acquisitionRoot 'gdc-files.json'
$ledgerPath = Join-Path $sourceRoot 'sample-ledger.jsonl'
$reservationPath = Join-Path $state "quota\reservations\source\$datasetId.reservation"

function Write-JsonAtomic([string] $Path, [object] $Value) {
    New-Item -ItemType Directory -Path (Split-Path -Parent $Path) -Force | Out-Null
    $partial = "$Path.partial"
    [IO.File]::WriteAllText($partial, (($Value | ConvertTo-Json -Depth 20) + "`n"),
        [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $partial -Destination $Path -Force
}
function Write-Status([string] $StateValue, [long] $Completed, [long] $Total, [string] $Detail) {
    Write-JsonAtomic $statusPath ([ordered]@{
        schema='pathlab.acquisition-status/1'; datasetId=$datasetId; state=$StateValue
        completedBytes=$Completed; totalBytes=$Total; detail=$Detail
        networkContext='interactive-user-acquisition-only'; analysisNetwork='disabled'
        updatedAt=[DateTimeOffset]::UtcNow.ToString('o')
    })
}
function Directory-Bytes([string] $Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Container)) { return 0L }
    $measure = Get-ChildItem -LiteralPath $Path -File -Recurse -ErrorAction Stop |
        Measure-Object Length -Sum
    if ($null -eq $measure.Sum) { return 0L }
    return [long]$measure.Sum
}
function Downloaded-Bytes($Files) {
    $total = 0L
    foreach ($file in $Files) {
        foreach ($candidate in @((Join-Path $downloadRoot $file.file_name),
                (Join-Path $downloadRoot "$($file.file_name).partial"),
                (Join-Path $sourceRoot $file.file_name))) {
            if (Test-Path -LiteralPath $candidate -PathType Leaf) {
                $total += [Math]::Min([long](Get-Item -LiteralPath $candidate).Length, [long]$file.bytes)
                break
            }
        }
    }
    return $total
}
function Query-Project([string] $Project) {
    $filter = @{op='and';content=@(
        @{op='in';content=@{field='cases.project.project_id';value=@($Project)}},
        @{op='in';content=@{field='data_type';value=@('Slide Image')}},
        @{op='in';content=@{field='data_format';value=@('SVS')}},
        @{op='in';content=@{field='access';value=@('open')}}
    )} | ConvertTo-Json -Depth 8 -Compress
    $fields = 'file_id,file_name,file_size,md5sum,access,data_type,data_format,' +
        'cases.case_id,cases.submitter_id,cases.project.project_id,cases.samples.sample_type'
    $response = Invoke-RestMethod -Method Post -Uri 'https://api.gdc.cancer.gov/files' -TimeoutSec 90 `
        -Body @{filters=$filter;format='JSON';fields=$fields;size='2000'}
    return @($response.data.hits | ForEach-Object {
        $case = $_.cases[0]
        $sampleTypes = @($case.samples.sample_type)
        $normal = @($sampleTypes | Where-Object { $_ -match 'Normal' }).Count -gt 0
        [pscustomobject]@{
            file_id=[string]$_.file_id; file_name=[string]$_.file_name
            bytes=[long]$_.file_size; md5=[string]$_.md5sum
            case_id=[string]$case.case_id; patient=[string]$case.submitter_id
            project=[string]$case.project.project_id
            phenotype=if($normal){'lung-normal'}else{'lung-tumor'}
            sample_type=($sampleTypes -join ',')
        }
    })
}

if ($Action -eq 'Status') {
    if (Test-Path -LiteralPath $statusPath -PathType Leaf) { Get-Content -LiteralPath $statusPath -Raw }
    else { '{"schema":"pathlab.acquisition-status/1","state":"not_started"}' }
    return
}
foreach ($directory in @($acquisitionRoot,$downloadRoot,(Split-Path -Parent $reservationPath))) {
    if (-not (Test-Path -LiteralPath $directory -PathType Container)) {
        New-Item -ItemType Directory -Path $directory -Force | Out-Null
    }
}

if ($Action -eq 'Start') {
    if (Test-Path -LiteralPath $statusPath -PathType Leaf) {
        $prior = Get-Content -LiteralPath $statusPath -Raw | ConvertFrom-Json
        if ($prior.state -eq 'completed') { $prior | ConvertTo-Json -Depth 8; return }
    }
    $selected = [Collections.Generic.List[object]]::new()
    foreach ($project in @('TCGA-LUAD','TCGA-LUSC')) {
        $split = if ($project -eq 'TCGA-LUAD') {'reference'} else {'query'}
        $hits = Query-Project $project
        $projectPatients = @{}
        foreach ($phenotype in @('lung-normal','lung-tumor')) {
            $candidates = @($hits | Where-Object phenotype -eq $phenotype |
                Sort-Object bytes,file_id)
            foreach ($candidate in $candidates) {
                if ($projectPatients.ContainsKey($candidate.patient)) { continue }
                $projectPatients[$candidate.patient] = $true
                $candidate | Add-Member -NotePropertyName split -NotePropertyValue $split
                $candidate | Add-Member -NotePropertyName source_group -NotePropertyValue $project
                $selected.Add($candidate)
                if (@($selected | Where-Object { $_.project -eq $project -and $_.phenotype -eq $phenotype }).Count -eq 10) { break }
            }
            if (@($selected | Where-Object { $_.project -eq $project -and $_.phenotype -eq $phenotype }).Count -ne 10) {
                throw "$project lacks ten patient-distinct $phenotype open slides."
            }
        }
    }
    if ($selected.Count -ne 40 -or @($selected.patient | Sort-Object -Unique).Count -ne 40) {
        throw 'The deterministic TCGA lung selection is not patient-disjoint.'
    }
    $requiredBytes = [long](($selected | Measure-Object bytes -Sum).Sum)
    $used = Directory-Bytes (Join-Path $state 'sources')
    $reserved = 0L
    Get-ChildItem -LiteralPath (Split-Path -Parent $reservationPath) -Filter '*.reservation' -File `
        -ErrorAction SilentlyContinue | Where-Object FullName -ne $reservationPath | ForEach-Object {
            $reserved += [long]([IO.File]::ReadAllText($_.FullName).Trim())
        }
    if ($requiredBytes -gt $sourceLimit - $used - $reserved) {
        throw 'The 45 GB new-source quota cannot reserve the bounded TCGA lung cohort.'
    }
    if (-not (Test-Path -LiteralPath $reservationPath -PathType Leaf)) {
        [IO.File]::WriteAllText("$reservationPath.partial", [string]$requiredBytes,
            [Text.UTF8Encoding]::new($false))
        Move-Item -LiteralPath "$reservationPath.partial" -Destination $reservationPath
    }
    Write-JsonAtomic $manifestPath ([ordered]@{
        schema='pathlab.gdc-acquisition-manifest/1'; datasetId=$datasetId
        api='https://api.gdc.cancer.gov/files'; dataEndpoint='https://api.gdc.cancer.gov/data/{file_id}'
        access='open'; policy='NIH Genomic Data Sharing and NCI GDC open-access policies'
        policyUrl='https://gdc.cancer.gov/about-gdc/gdc-policies'
        permittedUse='private-research'; frozenAt=[DateTimeOffset]::UtcNow.ToString('o')
        selection='10 normal and 10 tumor patient-distinct smallest open SVS files per project'
        files=$selected.ToArray(); totalBytes=$requiredBytes
    })
    $durableScript = Join-Path $acquisitionRoot 'acquire-tcga-lung-he.ps1'
    Copy-Item -LiteralPath $PSCommandPath -Destination $durableScript -Force
    $taskAction = New-ScheduledTaskAction -Execute 'powershell.exe' `
        -Argument "-NoProfile -ExecutionPolicy Bypass -File `"$durableScript`" -Action Run -StateRoot `"$state`""
    $trigger = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) `
        -RepetitionInterval (New-TimeSpan -Minutes 5) -RepetitionDuration (New-TimeSpan -Days 7)
    $principal = New-ScheduledTaskPrincipal -UserId ([Security.Principal.WindowsIdentity]::GetCurrent().Name) `
        -LogonType Interactive -RunLevel Limited
    $settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -MultipleInstances IgnoreNew `
        -ExecutionTimeLimit (New-TimeSpan -Days 7)
    Register-ScheduledTask -TaskName $taskName -Action $taskAction -Trigger $trigger -Principal $principal `
        -Settings $settings -Description 'Acquire checksum-bound open TCGA lung slides for PathLab.' -Force | Out-Null
    Write-Status 'transferring' 0 $requiredBytes 'Bounded open-access GDC acquisition scheduled.'
    Start-ScheduledTask -TaskName $taskName
    Get-Content -LiteralPath $statusPath -Raw
    return
}

$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
if ($manifest.schema -ne 'pathlab.gdc-acquisition-manifest/1' -or
        $manifest.permittedUse -ne 'private-research' -or @($manifest.files).Count -ne 40) {
    throw 'The frozen GDC acquisition manifest is invalid.'
}
$files = @($manifest.files)
$curl = (Get-Command curl.exe -ErrorAction Stop).Source
foreach ($file in $files) {
    $target = Join-Path $downloadRoot ([string]$file.file_name)
    if (Test-Path -LiteralPath $target -PathType Leaf) {
        if ((Get-Item -LiteralPath $target).Length -eq [long]$file.bytes -and
                (Get-FileHash -LiteralPath $target -Algorithm MD5).Hash.ToLowerInvariant() -eq $file.md5) { continue }
        throw "Existing TCGA acquisition file is invalid: $($file.file_name)"
    }
    $partial = "$target.partial"
    if (Test-Path -LiteralPath $partial -PathType Leaf) { Remove-Item -LiteralPath $partial -Force }
    $exitCode = -1
    for ($attempt=1; $attempt -le 3; $attempt++) {
        Write-Status 'transferring' (Downloaded-Bytes $files) ([long]$manifest.totalBytes) `
            "Downloading $($file.file_name) (attempt $attempt of 3)"
        $url = "https://api.gdc.cancer.gov/data/$($file.file_id)"
        $process = Start-Process -FilePath $curl -ArgumentList @('--fail','--location','--silent',
            '--show-error','--remove-on-error','--output',('"'+$partial+'"'),$url) -PassThru -NoNewWindow
        while (-not $process.HasExited) {
            Write-Status 'transferring' (Downloaded-Bytes $files) ([long]$manifest.totalBytes) `
                "Downloading $($file.file_name) (attempt $attempt of 3)"
            Start-Sleep -Seconds 10
            $process.Refresh()
        }
        $process.WaitForExit(); $exitCode=[int]$process.ExitCode
        if ($exitCode -eq 0) { break }
        Remove-Item -LiteralPath $partial -Force -ErrorAction SilentlyContinue
        if ($attempt -lt 3) { Start-Sleep -Seconds $(if($attempt -eq 1){5}else{30}) }
    }
    if ($exitCode -ne 0 -or -not (Test-Path -LiteralPath $partial -PathType Leaf) -or
            (Get-Item -LiteralPath $partial).Length -ne [long]$file.bytes -or
            (Get-FileHash -LiteralPath $partial -Algorithm MD5).Hash.ToLowerInvariant() -ne $file.md5) {
        Write-Status 'failed' (Downloaded-Bytes $files) ([long]$manifest.totalBytes) `
            "Download or checksum failed: $($file.file_name)"
        throw "TCGA lung acquisition failed closed: $($file.file_name)"
    }
    Move-Item -LiteralPath $partial -Destination $target
}

Write-Status 'validating' ([long]$manifest.totalBytes) ([long]$manifest.totalBytes) `
    'Validating GDC MD5 values and recording local SHA-256 checksums.'
New-Item -ItemType Directory -Path $sourceRoot -Force | Out-Null
$ledger = [Collections.Generic.List[string]]::new()
foreach ($file in $files) {
    $download = Join-Path $downloadRoot ([string]$file.file_name)
    if ((Get-FileHash -LiteralPath $download -Algorithm MD5).Hash.ToLowerInvariant() -ne $file.md5) {
        throw "Final GDC checksum changed: $($file.file_name)"
    }
    $target = Join-Path $sourceRoot ([string]$file.file_name)
    Move-Item -LiteralPath $download -Destination $target -Force
    $ledger.Add(([ordered]@{
        sampleId=[string]$file.file_id; source="NCI GDC/$($file.project)/$($file.file_name)"
        patientGroup=[string]$file.patient; slideGroup=[string]$file.file_id
        sha256=(Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()
        upstreamMd5=[string]$file.md5; bytes=[long]$file.bytes
        license='NIH-GDS/NCI-GDC-open-access-policy'; permittedUse='private-research'
        task='he-retrieval-qualification'; taskLabel=[string]$file.phenotype
        split=[string]$file.split; sourceGroup=[string]$file.source_group
    } | ConvertTo-Json -Compress))
}
[IO.File]::WriteAllLines($ledgerPath,$ledger,[Text.UTF8Encoding]::new($false))
Copy-Item -LiteralPath $manifestPath -Destination (Join-Path $sourceRoot 'gdc-files.json') -Force
Remove-Item -LiteralPath $reservationPath -Force
Write-Status 'completed' ([long]$manifest.totalBytes) ([long]$manifest.totalBytes) `
    'Forty patient-distinct open TCGA lung slides are checksum-verified and ledgered.'
Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue
