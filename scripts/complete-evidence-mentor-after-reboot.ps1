[CmdletBinding()]
param(
    [string] $StateRoot = 'D:\PathLabData\EvidenceMentor\state',
    [string] $ProgramRoot = 'C:\ProgramData\PathLab\EvidenceMentor'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$version = '2.1.1'
$taskName = 'PathLabEvidenceMentorPostRepair'
$acceptanceRoot = Join-Path $StateRoot 'acceptance\post-reboot-2.1.1'
$resultPath = Join-Path $acceptanceRoot 'result.json'
$logPath = Join-Path $acceptanceRoot 'continuation.log'
New-Item -ItemType Directory -Path $acceptanceRoot -Force | Out-Null

function Write-Result([string] $Status, [string] $Detail) {
    $value = [ordered]@{
        schema = 'pathlab.post-reboot-continuation/1'
        version = $version
        status = $Status
        detail = $Detail
        completedAt = [DateTimeOffset]::UtcNow.ToString('o')
    } | ConvertTo-Json -Depth 6
    $partial = "$resultPath.partial"
    [IO.File]::WriteAllText($partial, $value + "`n", [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $partial -Destination $resultPath -Force
}

function Set-Claims([bool] $Accepting) {
    $endpoint = Get-Content -LiteralPath (Join-Path $StateRoot 'endpoint.json') -Raw | ConvertFrom-Json
    $token = [IO.File]::ReadAllText((Join-Path $StateRoot 'ipc-token')).Trim()
    $action = if ($Accepting) { 'resume' } else { 'pause' }
    Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$($endpoint.port)/v1/control/$action" `
        -Headers @{ Authorization = "Bearer $token" } -ContentType 'application/json' -Body '{}' | Out-Null
}

function Install-OutboundDeny([string[]] $Programs) {
    $desiredNames = [Collections.Generic.List[string]]::new()
    $createdNames = [Collections.Generic.List[string]]::new()
    try {
        $index = 0
        foreach ($program in $Programs) {
            if (-not (Test-Path -LiteralPath $program -PathType Leaf)) {
                throw "Analysis executable is unavailable: $program"
            }
            $name = "PathLab Evidence Mentor outbound deny $version $index"
            $desiredNames.Add($name)
            if ($null -eq (Get-NetFirewallRule -DisplayName $name -ErrorAction SilentlyContinue)) {
                New-NetFirewallRule -DisplayName $name -Direction Outbound -Action Block `
                    -Program $program -Profile Any -ErrorAction Stop | Out-Null
                $createdNames.Add($name)
            }
            $actual = Get-NetFirewallRule -DisplayName $name -ErrorAction Stop |
                Get-NetFirewallApplicationFilter | Select-Object -ExpandProperty Program -First 1
            if ([IO.Path]::GetFullPath($actual) -ne [IO.Path]::GetFullPath($program)) {
                throw "Outbound-deny rule program mismatch: $name"
            }
            $index++
        }
        Get-NetFirewallRule -DisplayName 'PathLab Evidence Mentor outbound deny*' -ErrorAction SilentlyContinue |
            Where-Object { $desiredNames -notcontains $_.DisplayName } | Remove-NetFirewallRule
    } catch {
        foreach ($name in $createdNames) {
            Get-NetFirewallRule -DisplayName $name -ErrorAction SilentlyContinue |
                Remove-NetFirewallRule -ErrorAction SilentlyContinue
        }
        throw
    }
}

Start-Transcript -LiteralPath $logPath -Append | Out-Null
try {
    try { Set-Claims $false } catch { }
    $runtime = Join-Path $ProgramRoot "runtime\$version"
    $expectedPrograms = @((Join-Path $runtime 'jre\bin\java.exe'))
    $modelRoot = Join-Path $StateRoot 'models'
    if (Test-Path -LiteralPath $modelRoot) {
        $expectedPrograms += Get-ChildItem -LiteralPath $modelRoot -Filter '*.exe' -File -Recurse |
            Select-Object -ExpandProperty FullName
    }
    $expectedPrograms = @($expectedPrograms | ForEach-Object { [IO.Path]::GetFullPath($_).ToLowerInvariant() } |
        Sort-Object -Unique)
    Install-OutboundDeny $expectedPrograms
    $rules = @(Get-NetFirewallRule -DisplayName "PathLab Evidence Mentor outbound deny $version *" -ErrorAction Stop)
    $actualPrograms = @($rules | Get-NetFirewallApplicationFilter | Select-Object -ExpandProperty Program |
        ForEach-Object { [IO.Path]::GetFullPath($_).ToLowerInvariant() } | Sort-Object -Unique)
    if ($rules.Count -ne $expectedPrograms.Count -or
            (Compare-Object -ReferenceObject $expectedPrograms -DifferenceObject $actualPrograms)) {
        throw 'Installed outbound-deny rules do not exactly cover the analysis executables.'
    }

    $activeVersion = Join-Path $ProgramRoot 'active-version.txt'
    [IO.File]::WriteAllText("$activeVersion.partial", "$version`n", [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath "$activeVersion.partial" -Destination $activeVersion -Force
    & (Join-Path $ProgramRoot 'Test-PathLab-Evidence-Service.ps1') -Mode Inspect `
        -ProgramRoot $ProgramRoot -StateRoot $StateRoot -ReportRoot $acceptanceRoot
    if ($LASTEXITCODE -ne 0) { throw "Post-reboot service acceptance failed with exit code $LASTEXITCODE." }

    Set-Claims $true
    Write-Result 'PASS' 'Firewall rules, service health, and non-reboot acceptance passed; claims resumed.'
    Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue
} catch {
    try { Set-Claims $false } catch { }
    Write-Result 'FAIL' $_.Exception.Message
    throw
} finally {
    Stop-Transcript | Out-Null
}
