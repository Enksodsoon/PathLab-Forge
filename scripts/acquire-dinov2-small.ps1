[CmdletBinding()]
param(
    [string]$StateRoot
)

$ErrorActionPreference = 'Stop'
$repoId = 'facebook/dinov2-small'
$revision = 'ed25f3a31f01632728cabb09d1542f84ab7b0056'
$packId = 'he-dinov2-small-v1'
$packVersion = '1'
$modelQuotaBytes = 10GB
$files = @(
    [pscustomobject]@{
        Name = 'config.json'
        Bytes = 547L
        Sha256 = '1809f83e3bdb1609a501a610ad4a742f4fd8ae44d72ca4aa0df52d1f2ac8628d'
    },
    [pscustomobject]@{
        Name = 'preprocessor_config.json'
        Bytes = 436L
        Sha256 = '14e780d86fa1861f8751f868d7f45425b5feb55c38ca26f152ca5097ab30f828'
    },
    [pscustomobject]@{
        Name = 'model.safetensors'
        Bytes = 88249960L
        Sha256 = 'ae1e99fcefd534ed978cdeb8326f08030c96e28b7a81ffcbc98a857c84d14be1'
    }
)

if (-not $StateRoot) {
    $StateRoot = if (Test-Path -LiteralPath 'D:\PathLabData' -PathType Container) {
        'D:\PathLabData\EvidenceMentor\state'
    } else {
        Join-Path $env:LOCALAPPDATA 'PathLab\EvidenceMentor\state'
    }
}

$state = [IO.Path]::GetFullPath($StateRoot)
$modelsRoot = [IO.Path]::GetFullPath((Join-Path $state 'models'))
$installRoot = [IO.Path]::GetFullPath((Join-Path $modelsRoot "$packId\$packVersion"))
if (-not $installRoot.StartsWith($modelsRoot + [IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase)) {
    throw 'DINOv2 install path escaped the Evidence Mentor model root.'
}

$reservationRoot = Join-Path $state 'quota\reservations\models'
New-Item -ItemType Directory -Path $installRoot -Force | Out-Null
New-Item -ItemType Directory -Path $reservationRoot -Force | Out-Null

function Get-TreeBytes([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Container)) { return 0L }
    $sum = 0L
    Get-ChildItem -LiteralPath $Path -Recurse -File | ForEach-Object {
        $sum = [long]($sum + $_.Length)
    }
    return $sum
}

$requiredBytes = 0L
foreach ($file in $files) {
    $target = Join-Path $installRoot $file.Name
    if (Test-Path -LiteralPath $target -PathType Leaf) {
        $existing = Get-Item -LiteralPath $target
        $existingHash = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($existing.Length -ne $file.Bytes -or $existingHash -ne $file.Sha256) {
            throw "Existing DINOv2 artifact failed checksum: $($file.Name)"
        }
    } else {
        $requiredBytes = [long]($requiredBytes + $file.Bytes)
    }
}

$existingReceiptPath = Join-Path $installRoot 'acquisition.json'
if ($requiredBytes -eq 0 -and (Test-Path -LiteralPath $existingReceiptPath -PathType Leaf)) {
    $existingReceipt = Get-Content -Raw -LiteralPath $existingReceiptPath | ConvertFrom-Json
    if ($existingReceipt.schema -ne 'pathlab.model-acquisition/1' -or
            $existingReceipt.repository -ne $repoId -or $existingReceipt.revision -ne $revision) {
        throw 'Existing DINOv2 acquisition receipt is invalid.'
    }
    Write-Output "Already acquired $repoId@$revision under $installRoot"
    Write-Output 'Status remains not-evaluable until a checksum-pinned offline worker passes P2000 qualification.'
    return
}

$reservedBytes = 0L
Get-ChildItem -LiteralPath $reservationRoot -Filter '*.reservation' -File | ForEach-Object {
    $value = 0L
    if (-not [long]::TryParse(([IO.File]::ReadAllText($_.FullName).Trim()), [ref]$value) -or $value -lt 0) {
        throw "Invalid model quota reservation: $($_.Name)"
    }
    $reservedBytes = [long]($reservedBytes + $value)
}
$usedBytes = Get-TreeBytes $modelsRoot
if ($usedBytes -gt $modelQuotaBytes -or $reservedBytes -gt ($modelQuotaBytes - $usedBytes) -or
        $requiredBytes -gt ($modelQuotaBytes - $usedBytes - $reservedBytes)) {
    throw 'Evidence model quota is exhausted.'
}

$reservation = Join-Path $reservationRoot "acquire-$packId-$packVersion.reservation"
$reservationPartial = "$reservation.partial"
if ((Test-Path -LiteralPath $reservation) -or (Test-Path -LiteralPath $reservationPartial)) {
    throw 'A DINOv2 acquisition reservation already exists; inspect it before retrying.'
}
[IO.File]::WriteAllText($reservationPartial, [string]$requiredBytes)
Move-Item -LiteralPath $reservationPartial -Destination $reservation

try {
    foreach ($file in $files) {
        $target = Join-Path $installRoot $file.Name
        if (Test-Path -LiteralPath $target -PathType Leaf) { continue }
        $partial = "$target.partial"
        if (Test-Path -LiteralPath $partial) {
            throw "Partial DINOv2 artifact requires review before retry: $partial"
        }
        $uri = "https://huggingface.co/$repoId/resolve/$revision/$($file.Name)"
        Invoke-WebRequest -Uri $uri -OutFile $partial -MaximumRedirection 10
        $download = Get-Item -LiteralPath $partial
        $hash = (Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($download.Length -ne $file.Bytes -or $hash -ne $file.Sha256) {
            throw "Downloaded DINOv2 artifact failed checksum: $($file.Name)"
        }
        Move-Item -LiteralPath $partial -Destination $target
    }

    $receipt = [ordered]@{
        schema = 'pathlab.model-acquisition/1'
        packId = $packId
        packVersion = $packVersion
        repository = $repoId
        revision = $revision
        license = 'Apache-2.0'
        permittedUse = 'private-research'
        acquiredAt = [DateTimeOffset]::UtcNow.ToString('O')
        artifacts = @($files | ForEach-Object {
            [ordered]@{ name = $_.Name; bytes = $_.Bytes; sha256 = $_.Sha256 }
        })
        analysisNetwork = 'disabled'
        activationStatus = 'not-evaluable-worker-not-installed'
    }
    $receiptPath = Join-Path $installRoot 'acquisition.json'
    $receiptPartial = "$receiptPath.partial"
    $receipt | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $receiptPartial -Encoding utf8NoBOM
    Move-Item -LiteralPath $receiptPartial -Destination $receiptPath -Force
} finally {
    if (Test-Path -LiteralPath $reservation -PathType Leaf) {
        Remove-Item -LiteralPath $reservation -Force
    }
}

Write-Output "Acquired $repoId@$revision under $installRoot"
Write-Output 'Status remains not-evaluable until a checksum-pinned offline worker passes P2000 qualification.'
