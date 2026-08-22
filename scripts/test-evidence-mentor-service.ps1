[CmdletBinding(SupportsShouldProcess)]
param(
    [ValidateSet('Inspect','ServiceRestart','PrepareReboot','VerifyReboot','Summary')]
    [string] $Mode = 'Inspect',
    [string] $ProgramRoot = 'C:\ProgramData\PathLab\EvidenceMentor',
    [string] $StateRoot = 'D:\PathLabData\EvidenceMentor\state',
    [string] $ReportRoot,
    [string] $JobRequestPath,
    [string] $JobId,
    [ValidateRange(1, 120)] [int] $TimeoutMinutes = 10,
    [switch] $AllowIncomplete
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$serviceName = 'PathLabEvidenceMentor'
$expectedWinSwSha256 = '05b82d46ad331cc16bdc00de5c6332c1ef818df8ceefcd49c726553209b3a0da'
$checks = [Collections.Generic.List[object]]::new()
$startedAt = [DateTimeOffset]::UtcNow
if (-not $ReportRoot) { $ReportRoot = Join-Path $StateRoot 'acceptance' }
$ReportRoot = [IO.Path]::GetFullPath($ReportRoot)
$StateRoot = [IO.Path]::GetFullPath($StateRoot)
$ProgramRoot = [IO.Path]::GetFullPath($ProgramRoot)
if ($JobId -and $JobId -notmatch '^acceptance-[a-f0-9]{8,64}$') {
    throw 'JobId must be a non-identifying acceptance ID such as acceptance-0123abcd.'
}

function Add-Check([string] $Name, [string] $Status, [string] $Detail, [hashtable] $Evidence = @{}) {
    if ($Status -notin @('PASS','FAIL','NOT_EVALUABLE')) { throw "Invalid acceptance status: $Status" }
    $checks.Add([ordered]@{ name = $Name; status = $Status; detail = $Detail; evidence = $Evidence })
}

function Test-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    return [Security.Principal.WindowsPrincipal]::new($identity).IsInRole(
        [Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Read-Endpoint {
    $path = Join-Path $StateRoot 'endpoint.json'
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return $null }
    try {
        $endpoint = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
        if ($endpoint.schema -ne 'pathlab.runner-endpoint/1' -or $endpoint.port -lt 1 -or $endpoint.port -gt 65535) { return $null }
        return $endpoint
    } catch { return $null }
}

function Read-Token {
    $path = Join-Path $StateRoot 'ipc-token'
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return $null }
    $value = (Get-Content -LiteralPath $path -Raw).Trim()
    if ($value.Length -lt 32) { return $null }
    return $value
}

function Invoke-WebRequestCompat([hashtable] $Parameters) {
    $request = @{} + $Parameters
    if ($PSVersionTable.PSVersion.Major -le 5) {
        $request.UseBasicParsing = $true
    } else {
        $request.SkipHttpErrorCheck = $true
    }
    try {
        return Invoke-WebRequest @request
    } catch [Net.WebException] {
        $response = $_.Exception.Response
        if ($null -eq $response) { throw }
        $stream = $response.GetResponseStream()
        try {
            $reader = [IO.StreamReader]::new($stream)
            try { $content = $reader.ReadToEnd() } finally { $reader.Dispose() }
        } finally { if ($null -ne $stream) { $stream.Dispose() } }
        return [pscustomobject]@{
            StatusCode = [int]$response.StatusCode
            Content = $content
            Headers = $response.Headers
        }
    }
}

function Invoke-Runner([string] $Method, [string] $Path, [object] $Body = $null) {
    $endpoint = Read-Endpoint
    $token = Read-Token
    if ($null -eq $endpoint -or $null -eq $token) { throw 'Runner endpoint or token is unavailable.' }
    $parameters = @{
        Method = $Method
        Uri = "http://127.0.0.1:$($endpoint.port)$Path"
        Headers = @{ Authorization = "Bearer $token" }
        TimeoutSec = 3
    }
    if ($null -ne $Body) {
        $parameters.ContentType = 'application/json'
        $parameters.Body = $Body | ConvertTo-Json -Compress -Depth 8
    }
    return Invoke-WebRequestCompat $parameters
}

function Wait-Runner([string] $PreviousBootId = '', [int] $Seconds = 60) {
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($Seconds)
    do {
        Start-Sleep -Milliseconds 500
        $endpoint = Read-Endpoint
        if ($null -ne $endpoint -and ($PreviousBootId -eq '' -or $endpoint.bootId -ne $PreviousBootId)) {
            try {
                $response = Invoke-Runner 'GET' '/health'
                if ($response.StatusCode -eq 200) { return $endpoint }
            } catch { }
        }
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    return $null
}

function Test-Acl([string] $Path, [string] $Identity, [Security.AccessControl.FileSystemRights] $RequiredRights) {
    if (-not (Test-Path -LiteralPath $Path)) { return $false }
    $acl = Get-Acl -LiteralPath $Path
    foreach ($entry in $acl.Access) {
        $allowsIdentity = $entry.AccessControlType -eq 'Allow' -and $entry.IdentityReference.Value -ieq $Identity
        $hasRights = ($entry.FileSystemRights -band $RequiredRights) -eq $RequiredRights
        if ($allowsIdentity -and $hasRights) { return $true }
    }
    return $false
}

function Write-Atomic([string] $Path, [string] $Content) {
    $partial = "$Path.partial"
    [IO.File]::WriteAllText($partial, $Content, [Text.UTF8Encoding]::new($false))
    if (Test-Path -LiteralPath $Path -PathType Leaf) {
        $backup = "$Path.backup-$([Guid]::NewGuid().ToString('N'))"
        try {
            [IO.File]::Replace($partial, $Path, $backup)
        } finally {
            if (Test-Path -LiteralPath $backup -PathType Leaf) {
                Remove-Item -LiteralPath $backup -Force
            }
        }
    } else {
        [IO.File]::Move($partial, $Path)
    }
}

function Write-AcceptanceReport {
    New-Item -ItemType Directory -Path $ReportRoot -Force | Out-Null
    $failures = @($checks | Where-Object status -eq 'FAIL').Count
    $notEvaluable = @($checks | Where-Object status -eq 'NOT_EVALUABLE').Count
    $verdict = if ($failures -gt 0) { 'FAIL' } elseif ($notEvaluable -gt 0) { 'NOT_EVALUABLE' } else { 'PASS' }
    $report = [ordered]@{
        schema = 'pathlab.service-acceptance/1'
        service = $serviceName
        runtimeVersion = if (Test-Path -LiteralPath (Join-Path $ProgramRoot 'active-version.txt') -PathType Leaf) {
            (Get-Content -LiteralPath (Join-Path $ProgramRoot 'active-version.txt') -Raw).Trim()
        } else { '' }
        mode = $Mode
        startedAt = $startedAt.ToString('O')
        completedAt = [DateTimeOffset]::UtcNow.ToString('O')
        verdict = $verdict
        counts = @{ pass = @($checks | Where-Object status -eq 'PASS').Count; fail = $failures; notEvaluable = $notEvaluable }
        privacy = @{ containsTokens = $false; containsSlidePaths = $false; containsScientificOutcomes = $false }
        checks = $checks
    }
    $stamp = [DateTimeOffset]::UtcNow.ToString('yyyyMMdd-HHmmss-fff')
    $jsonPath = Join-Path $ReportRoot "service-acceptance-$stamp.json"
    $markdownPath = Join-Path $ReportRoot "service-acceptance-$stamp.md"
    Write-Atomic $jsonPath ($report | ConvertTo-Json -Depth 12)
    $lines = [Collections.Generic.List[string]]::new()
    $lines.Add('# PathLab Evidence Mentor service acceptance')
    $lines.Add('')
    $lines.Add("- Verdict: **$verdict**")
    $lines.Add("- Mode: ``$Mode``")
    $lines.Add("- Completed: $($report.completedAt)")
    $lines.Add('')
    $lines.Add('| Check | Status | Detail |')
    $lines.Add('|---|---|---|')
    foreach ($check in $checks) {
        $detail = ([string]$check.detail).Replace('|','\|').Replace("`r",' ').Replace("`n",' ')
        $lines.Add("| $($check.name) | $($check.status) | $detail |")
    }
    Write-Atomic $markdownPath ($lines -join [Environment]::NewLine)
    [ordered]@{ verdict = $verdict; json = $jsonPath; markdown = $markdownPath }
}

function Add-StaticChecks {
    $service = Get-CimInstance Win32_Service -Filter "Name='$serviceName'" -ErrorAction SilentlyContinue
    if ($null -eq $service) {
        Add-Check 'service-installed' 'FAIL' 'Windows service is not installed.'
        Add-Check 'service-running' 'NOT_EVALUABLE' 'Service installation is required first.'
        Add-Check 'service-identity' 'NOT_EVALUABLE' 'Service installation is required first.'
        Add-Check 'service-sid-enabled' 'NOT_EVALUABLE' 'Service installation is required first.'
    } else {
        Add-Check 'service-installed' 'PASS' 'Windows service is installed.' @{ startMode = $service.StartMode }
        Add-Check 'service-running' $(if ($service.State -eq 'Running') {'PASS'} else {'FAIL'}) "Service state is $($service.State)."
        Add-Check 'service-identity' $(if ($service.StartName -ieq 'NT AUTHORITY\LocalService') {'PASS'} else {'FAIL'}) "Service identity is $($service.StartName)."
        $sidType = (& sc.exe qsidtype $serviceName 2>$null) -join [Environment]::NewLine
        $sidEnabled = $LASTEXITCODE -eq 0 -and $sidType -match 'UNRESTRICTED'
        Add-Check 'service-sid-enabled' $(if ($sidEnabled) {'PASS'} else {'FAIL'}) 'The service SID must be enabled before SID-only runtime ACLs can authorize startup.'
    }

    $serviceRegistry = "HKLM:\SYSTEM\CurrentControlSet\Services\$serviceName"
    if (Test-Path -LiteralPath $serviceRegistry) {
        $registry = Get-ItemProperty -LiteralPath $serviceRegistry
        $delayedProperty = $registry.PSObject.Properties['DelayedAutostart']
        $failureProperty = $registry.PSObject.Properties['FailureActions']
        $delayed = $null -ne $delayedProperty -and $delayedProperty.Value -eq 1
        $recovery = $null -ne $failureProperty -and $null -ne $failureProperty.Value -and $failureProperty.Value.Length -gt 0
        Add-Check 'delayed-auto-start' $(if ($delayed) {'PASS'} else {'FAIL'}) 'Delayed automatic startup registry flag.' @{ enabled = $delayed }
        Add-Check 'service-recovery-policy' $(if ($recovery) {'PASS'} else {'FAIL'}) 'Windows service recovery actions are configured.'
    } else {
        Add-Check 'delayed-auto-start' 'NOT_EVALUABLE' 'Service registry key is absent.'
        Add-Check 'service-recovery-policy' 'NOT_EVALUABLE' 'Service registry key is absent.'
    }

    $activeVersionPath = Join-Path $ProgramRoot 'active-version.txt'
    $wrapperPath = Join-Path $ProgramRoot 'PathLabEvidenceMentor.exe'
    if (Test-Path -LiteralPath $wrapperPath -PathType Leaf) {
        $sha = (Get-FileHash -LiteralPath $wrapperPath -Algorithm SHA256).Hash.ToLowerInvariant()
        Add-Check 'winsw-checksum' $(if ($sha -eq $expectedWinSwSha256) {'PASS'} else {'FAIL'}) 'Installed WinSW must match the pinned checksum.' @{ sha256 = $sha }
    } else { Add-Check 'winsw-checksum' 'NOT_EVALUABLE' 'WinSW wrapper is not installed.' }
    if (Test-Path -LiteralPath $activeVersionPath -PathType Leaf) {
        Add-Check 'active-runtime' 'PASS' 'An active side-by-side runtime version is recorded.' @{ version = (Get-Content -LiteralPath $activeVersionPath -Raw).Trim() }
    } else { Add-Check 'active-runtime' 'NOT_EVALUABLE' 'Active runtime marker is absent.' }

    $serviceIdentity = "NT SERVICE\$serviceName"
    Add-Check 'runtime-acl' $(if (Test-Acl $ProgramRoot $serviceIdentity ([Security.AccessControl.FileSystemRights]::ReadAndExecute)) {'PASS'} else {'FAIL'}) 'Service SID requires runtime read/execute access.'
    Add-Check 'state-acl' $(if (Test-Acl $StateRoot $serviceIdentity ([Security.AccessControl.FileSystemRights]::Modify)) {'PASS'} else {'FAIL'}) 'Service SID requires state modify access.'

    try {
        $rules = @(Get-NetFirewallRule -DisplayName 'PathLab Evidence Mentor outbound deny*' -ErrorAction Stop |
            Where-Object { $_.Enabled -eq 'True' -and $_.Direction -eq 'Outbound' -and $_.Action -eq 'Block' })
        $expectedExecutables = [Collections.Generic.List[string]]::new()
        $activeRuntime = if (Test-Path -LiteralPath $activeVersionPath -PathType Leaf) {
            (Get-Content -LiteralPath $activeVersionPath -Raw).Trim()
        } else { '' }
        if ($activeRuntime) {
            $runtimeJava = Join-Path $ProgramRoot "runtime\$activeRuntime\jre\bin\java.exe"
            if (Test-Path -LiteralPath $runtimeJava -PathType Leaf) { $expectedExecutables.Add([IO.Path]::GetFullPath($runtimeJava)) }
        }
        $modelRoot = Join-Path $StateRoot 'models'
        if (Test-Path -LiteralPath $modelRoot -PathType Container) {
            foreach ($worker in Get-ChildItem -LiteralPath $modelRoot -Filter '*.exe' -File -Recurse) {
                $expectedExecutables.Add($worker.FullName)
            }
        }
        $blockedExecutables = @($rules | Get-NetFirewallApplicationFilter | ForEach-Object { [IO.Path]::GetFullPath($_.Program) })
        $missing = @($expectedExecutables | Where-Object { $_ -notin $blockedExecutables })
        $covered = $expectedExecutables.Count -gt 0 -and $missing.Count -eq 0
        Add-Check 'outbound-deny-rules' $(if ($covered) {'PASS'} else {'FAIL'}) 'Every installed analysis executable requires an enabled outbound-deny rule.' @{
            expectedExecutableCount = $expectedExecutables.Count; ruleCount = $rules.Count; missingCount = $missing.Count
        }
    } catch { Add-Check 'outbound-deny-rules' 'NOT_EVALUABLE' 'Firewall rules could not be inspected.' }

    $endpoint = Read-Endpoint
    if ($null -eq $endpoint) {
        Add-Check 'dynamic-endpoint' 'FAIL' 'A valid endpoint.json is not published.'
        Add-Check 'authenticated-health' 'NOT_EVALUABLE' 'Runner endpoint is unavailable.'
        Add-Check 'dashboard-session-security' 'NOT_EVALUABLE' 'Runner endpoint is unavailable.'
    } else {
        Add-Check 'dynamic-endpoint' 'PASS' 'Runner published a token-free dynamic loopback endpoint.' @{
            port = [int]$endpoint.port; bootId = [string]$endpoint.bootId; serviceVersion = [string]$endpoint.serviceVersion
        }
        try {
            $health = Invoke-Runner 'GET' '/health'
            Add-Check 'authenticated-health' $(if ($health.StatusCode -eq 200) {'PASS'} else {'FAIL'}) 'Authenticated loopback health response.' @{ statusCode = $health.StatusCode }
        } catch { Add-Check 'authenticated-health' 'FAIL' 'Authenticated health request failed.' }
        try {
            $origin = "http://127.0.0.1:$($endpoint.port)"
            $unauthorized = Invoke-WebRequestCompat @{ Uri = "$origin/v1/status"; TimeoutSec = 3 }
            $created = Invoke-Runner 'POST' '/v1/dashboard-sessions'
            $code = ($created.Content | ConvertFrom-Json).code
            $exchangeBody = @{ code = $code } | ConvertTo-Json -Compress
            $first = Invoke-WebRequestCompat @{ Method = 'Post'; Uri = "$origin/v1/dashboard-session/exchange";
                Headers = @{ Origin = $origin }; ContentType = 'application/json'; Body = $exchangeBody; TimeoutSec = 3 }
            $reuse = Invoke-WebRequestCompat @{ Method = 'Post'; Uri = "$origin/v1/dashboard-session/exchange";
                Headers = @{ Origin = $origin }; ContentType = 'application/json'; Body = $exchangeBody; TimeoutSec = 3 }
            $setCookie = [string]($first.Headers['Set-Cookie'] -join ';')
            $secure = $unauthorized.StatusCode -eq 401 -and $created.StatusCode -eq 201 `
                -and $first.StatusCode -eq 200 -and $reuse.StatusCode -eq 401 `
                -and $setCookie -match 'HttpOnly' -and $setCookie -match 'SameSite=Strict'
            Add-Check 'dashboard-session-security' $(if ($secure) {'PASS'} else {'FAIL'}) 'Bearer protection and one-time dashboard exchange.' @{
                unauthorized = $unauthorized.StatusCode; created = $created.StatusCode; exchanged = $first.StatusCode; reused = $reuse.StatusCode
            }
        } catch { Add-Check 'dashboard-session-security' 'FAIL' 'Dashboard session security check failed.' }
    }

    try {
        $gpu = & nvidia-smi.exe --query-gpu=name,driver_version,memory.total --format=csv,noheader,nounits 2>$null
        if ($LASTEXITCODE -eq 0 -and $gpu) {
            $parts = (@($gpu)[0]).Split(',')
            Add-Check 'gpu-visible' 'PASS' 'NVIDIA GPU telemetry is visible to the current acceptance process.' @{
                name = $parts[0].Trim(); driver = $parts[1].Trim(); memoryMiB = [int]($parts[2].Trim())
            }
        } else { Add-Check 'gpu-visible' 'FAIL' 'nvidia-smi did not return GPU telemetry.' }
    } catch { Add-Check 'gpu-visible' 'FAIL' 'nvidia-smi is unavailable.' }
}

function Submit-BoundedJob {
    if (-not $JobRequestPath) { Add-Check 'bounded-job' 'NOT_EVALUABLE' 'No acceptance job request was supplied.'; return $null }
    $request = [IO.Path]::GetFullPath($JobRequestPath)
    $statePrefix = $StateRoot.TrimEnd('\') + '\'
    if (-not $request.StartsWith($statePrefix, [StringComparison]::OrdinalIgnoreCase)) {
        Add-Check 'bounded-job' 'FAIL' 'Acceptance request must be staged under the protected state root.'
        return $null
    }
    if (-not (Test-Path -LiteralPath $request -PathType Leaf)) { Add-Check 'bounded-job' 'FAIL' 'Acceptance request file is missing.'; return $null }
    $resolvedJobId = if ($JobId) { $JobId } else { 'acceptance-' + [Guid]::NewGuid().ToString('N') }
    try {
        $response = Invoke-Runner 'POST' '/v1/jobs' @{ id = $resolvedJobId; requestPath = $request }
        if ($response.StatusCode -ne 202) { Add-Check 'bounded-job-submission' 'FAIL' "Runner returned HTTP $($response.StatusCode)."; return $null }
        Add-Check 'bounded-job-submission' 'PASS' 'Runner accepted ownership of the immutable bounded request.' @{ jobId = $resolvedJobId }
        return $resolvedJobId
    } catch { Add-Check 'bounded-job-submission' 'FAIL' 'Bounded job submission failed.'; return $null }
}

function Test-Session0GpuResult([string] $ResolvedJobId, [object] $Job) {
    $resultPath = Join-Path $StateRoot "worker-output\$ResolvedJobId\result.json"
    try {
        if (-not (Test-Path -LiteralPath $resultPath -PathType Leaf)) {
            return [pscustomobject]@{ Passed = $false; Evidence = @{ reason = 'worker-result-missing' } }
        }
        $result = Get-Content -LiteralPath $resultPath -Raw | ConvertFrom-Json
        $runtime = $result.runtime
        $passed = $result.schema -eq 'pathlab.model-worker-result/1' -and
            $result.status -eq 'completed' -and
            $result.packManifestSha256 -eq $Job.packSha256 -and
            [string]$runtime.device -match 'Quadro P2000' -and
            [string]$runtime.cuda -eq '12.6' -and
            [string]$runtime.architecture -eq 'sm_61' -and
            [string]$runtime.analysisNetwork -eq 'disabled' -and
            [int]$runtime.tiles -gt 0 -and
            [double]$runtime.elapsedSeconds -gt 0 -and
            [double]$runtime.peakVramMiB -gt 0 -and
            [double]$runtime.peakVramMiB -le 4608 -and
            [double]$runtime.peakRamMiB -gt 0 -and
            [double]$runtime.peakRamMiB -le 16384
        return [pscustomobject]@{
            Passed = $passed
            Evidence = @{
                resultSha256 = (Get-FileHash -LiteralPath $resultPath -Algorithm SHA256).Hash.ToLowerInvariant()
                device = [string]$runtime.device
                cuda = [string]$runtime.cuda
                architecture = [string]$runtime.architecture
                tiles = [int]$runtime.tiles
                batchSize = [int]$runtime.batchSize
                elapsedSeconds = [double]$runtime.elapsedSeconds
                peakVramMiB = [double]$runtime.peakVramMiB
                peakRamMiB = [double]$runtime.peakRamMiB
                analysisNetwork = [string]$runtime.analysisNetwork
            }
        }
    } catch {
        return [pscustomobject]@{ Passed = $false; Evidence = @{ reason = 'worker-result-invalid' } }
    }
}

function Wait-Job([string] $ResolvedJobId, [bool] $RestartWhenActive) {
    if (-not $ResolvedJobId) { return $false }
    $deadline = [DateTimeOffset]::UtcNow.AddMinutes($TimeoutMinutes)
    $restarted = $false
    $pollMilliseconds = if ($RestartWhenActive) { 50 } else { 1000 }
    do {
        try {
            $response = Invoke-Runner 'GET' "/v1/jobs/$ResolvedJobId"
            if ($response.StatusCode -ne 200) { Start-Sleep -Milliseconds $pollMilliseconds; continue }
            $job = $response.Content | ConvertFrom-Json
            if ($RestartWhenActive -and -not $restarted -and $job.state -in @('validating','running','refining','packaging')) {
                if (-not (Test-Administrator)) { Add-Check 'service-restart' 'NOT_EVALUABLE' 'Administrator privileges are required.'; $RestartWhenActive = $false; continue }
                $before = Read-Endpoint
                if ($PSCmdlet.ShouldProcess($serviceName, "Restart service while bounded job $ResolvedJobId is active")) {
                    Restart-Service -Name $serviceName -Force
                    $after = Wait-Runner -PreviousBootId $before.bootId -Seconds 60
                    if ($null -eq $after) { Add-Check 'service-restart' 'FAIL' 'Runner did not return with a new boot ID.'; return $false }
                    Add-Check 'service-restart' 'PASS' 'Runner returned after restart with a new boot ID.' @{ previousBootId = $before.bootId; bootId = $after.bootId }
                    $restarted = $true
                }
            }
            if ($job.state -in @('completed','abstained','unsupported','failed','cancelled')) {
                $passed = $job.state -in @('completed','abstained') -and $job.finalArtifactSha256 -match '^[a-f0-9]{64}$'
                Add-Check 'bounded-job-completion' $(if ($passed) {'PASS'} else {'FAIL'}) 'Bounded job reached a terminal state.' @{
                    jobId = $ResolvedJobId; state = $job.state; lane = $job.lane; failureCode = $job.failureCode; finalArtifactSha256 = $job.finalArtifactSha256
                }
                if ($job.lane -eq 'gpu') {
                    $gpuResult = Test-Session0GpuResult $ResolvedJobId $job
                    $gpuPassed = $passed -and $gpuResult.Passed
                    $gpuEvidence = @{} + $gpuResult.Evidence
                    $gpuEvidence.jobId = $ResolvedJobId
                    $gpuEvidence.state = $job.state
                    Add-Check 'session0-gpu-inference' $(if ($gpuPassed) {'PASS'} else {'FAIL'}) 'The retained worker result proves bounded offline CUDA inference on the P2000.' $gpuEvidence
                } else { Add-Check 'session0-gpu-inference' 'NOT_EVALUABLE' 'The bounded acceptance job did not use the GPU lane.' }
                return $passed
            }
        } catch { }
        Start-Sleep -Milliseconds $pollMilliseconds
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    Add-Check 'bounded-job-completion' 'FAIL' 'Bounded job did not complete before the acceptance timeout.' @{ jobId = $ResolvedJobId }
    return $false
}

function Wait-JobActive([string] $ResolvedJobId, [int] $Seconds = 60) {
    if (-not $ResolvedJobId) { return $false }
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($Seconds)
    do {
        try {
            $response = Invoke-Runner 'GET' "/v1/jobs/$ResolvedJobId"
            if ($response.StatusCode -ne 200) { Start-Sleep -Seconds 1; continue }
            $job = $response.Content | ConvertFrom-Json
            if ($job.state -in @('validating','running','refining','packaging')) {
                Add-Check 'reboot-job-active' 'PASS' 'The bounded job is durably active before manual reboot.' @{
                    jobId = $ResolvedJobId; state = $job.state; lane = $job.lane; updatedAt = $job.updatedAt
                }
                return $true
            }
            if ($job.state -in @('completed','abstained','unsupported','failed','cancelled')) {
                Add-Check 'reboot-job-active' 'FAIL' 'The bounded job reached a terminal state before the reboot challenge.' @{
                    jobId = $ResolvedJobId; state = $job.state
                }
                return $false
            }
        } catch { }
        Start-Sleep -Seconds 1
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    Add-Check 'reboot-job-active' 'FAIL' 'The bounded job did not become active before the reboot challenge timeout.' @{ jobId = $ResolvedJobId }
    return $false
}

function Prepare-RebootChallenge([string] $ResolvedJobId) {
    $endpoint = Read-Endpoint
    if ($null -eq $endpoint) { Add-Check 'reboot-challenge' 'FAIL' 'Runner endpoint is required before preparing reboot verification.'; return }
    $os = Get-CimInstance Win32_OperatingSystem
    $jobAtChallenge = $null
    if ($ResolvedJobId) {
        try {
            $jobResponse = Invoke-Runner 'GET' "/v1/jobs/$ResolvedJobId"
            if ($jobResponse.StatusCode -eq 200) { $jobAtChallenge = $jobResponse.Content | ConvertFrom-Json }
        } catch { }
    }
    $challenge = [ordered]@{
        schema = 'pathlab.service-reboot-challenge/1'
        createdAt = [DateTimeOffset]::UtcNow.ToString('O')
        osBootTime = ([DateTimeOffset]$os.LastBootUpTime).ToUniversalTime().ToString('O')
        runnerBootId = [string]$endpoint.bootId
        jobId = if ($ResolvedJobId) { $ResolvedJobId } else { '' }
        jobState = if ($null -ne $jobAtChallenge) { [string]$jobAtChallenge.state } else { '' }
        jobUpdatedAt = if ($null -ne $jobAtChallenge) { [string]$jobAtChallenge.updatedAt } else { '' }
    }
    New-Item -ItemType Directory -Path $ReportRoot -Force | Out-Null
    Write-Atomic (Join-Path $ReportRoot 'reboot-challenge.json') ($challenge | ConvertTo-Json -Depth 5)
    Add-Check 'reboot-challenge' 'PASS' 'Challenge recorded. Reboot manually, then run VerifyReboot.' @{ hasJob = [bool]$ResolvedJobId }
}

function Verify-RebootChallenge {
    $path = Join-Path $ReportRoot 'reboot-challenge.json'
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { Add-Check 'reboot-continuation' 'NOT_EVALUABLE' 'No reboot challenge is present.'; return }
    $challenge = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
    if ($challenge.schema -ne 'pathlab.service-reboot-challenge/1') { Add-Check 'reboot-continuation' 'FAIL' 'Reboot challenge schema is invalid.'; return }
    $os = Get-CimInstance Win32_OperatingSystem
    $currentBoot = ([DateTimeOffset]$os.LastBootUpTime).ToUniversalTime()
    $endpoint = Wait-Runner -Seconds 60
    $passed = $currentBoot -gt [DateTimeOffset]::Parse($challenge.osBootTime) -and $null -ne $endpoint -and $endpoint.bootId -ne $challenge.runnerBootId
    Add-Check 'reboot-continuation' $(if ($passed) {'PASS'} else {'FAIL'}) 'OS and runner boot identities must both change and recover without login.' @{
        osBootChanged = $currentBoot -gt [DateTimeOffset]::Parse($challenge.osBootTime)
        runnerBootChanged = $null -ne $endpoint -and $endpoint.bootId -ne $challenge.runnerBootId
    }
    if ($passed -and $challenge.jobId) {
        $continued = Wait-Job ([string]$challenge.jobId) $false
        $completedAfterBoot = $false
        $jobUpdatedAt = ''
        try {
            $jobResponse = Invoke-Runner 'GET' "/v1/jobs/$([string]$challenge.jobId)"
            if ($jobResponse.StatusCode -eq 200) {
                $job = $jobResponse.Content | ConvertFrom-Json
                $jobUpdatedAt = [string]$job.updatedAt
                $completedAfterBoot = [DateTimeOffset]::Parse($jobUpdatedAt) -gt $currentBoot
            }
        } catch { }
        $continued = $continued -and $completedAfterBoot
        Add-Check 'reboot-job-continuation' $(if ($continued) {'PASS'} else {'FAIL'}) 'The pre-reboot bounded job must complete after the new OS boot, not before it.' @{
            jobId = [string]$challenge.jobId; jobUpdatedAt = $jobUpdatedAt; currentBoot = $currentBoot.ToString('O'); completedAfterBoot = $completedAfterBoot
        }
    } elseif (-not $challenge.jobId) {
        Add-Check 'reboot-job-continuation' 'NOT_EVALUABLE' 'The reboot challenge did not include a bounded job.'
    }
}

function Add-SummaryChecks {
    $required = @(
        'bounded-job-submission','bounded-job-completion','session0-gpu-inference',
        'service-restart','reboot-job-active','reboot-continuation','reboot-job-continuation'
    )
    $currentRuntime = if (Test-Path -LiteralPath (Join-Path $ProgramRoot 'active-version.txt') -PathType Leaf) {
        (Get-Content -LiteralPath (Join-Path $ProgramRoot 'active-version.txt') -Raw).Trim()
    } else { '' }
    $reports = [Collections.Generic.List[object]]::new()
    if (Test-Path -LiteralPath $ReportRoot -PathType Container) {
        foreach ($file in Get-ChildItem -LiteralPath $ReportRoot -Filter 'service-acceptance-*.json' -File) {
            try {
                $report = Get-Content -LiteralPath $file.FullName -Raw | ConvertFrom-Json
                if ($report.schema -eq 'pathlab.service-acceptance/1' -and $report.mode -ne 'Summary' `
                    -and $currentRuntime -and $report.runtimeVersion -eq $currentRuntime) { $reports.Add($report) }
            } catch { }
        }
    }
    foreach ($name in $required) {
        $passing = @($reports | ForEach-Object { $_.checks } | Where-Object { $_.name -eq $name -and $_.status -eq 'PASS' } |
            Select-Object -First 1)
        if ($passing.Count -gt 0) {
            Add-Check $name 'PASS' 'A prior host acceptance report contains passing evidence.'
        } else {
            Add-Check $name 'NOT_EVALUABLE' 'No passing host acceptance report is available yet.'
        }
    }
}

if ($Mode -eq 'Summary') {
    Add-StaticChecks
    Add-SummaryChecks
} else {
    Add-StaticChecks
    $resolvedJobId = $null
    if ($JobRequestPath) { $resolvedJobId = Submit-BoundedJob }
    switch ($Mode) {
        'ServiceRestart' {
            if ($null -eq $resolvedJobId) { Add-Check 'service-restart' 'NOT_EVALUABLE' 'A bounded job request is required for restart recovery.' }
            else {
                $null = Wait-Job $resolvedJobId $true
                if (@($checks | Where-Object name -eq 'service-restart').Count -eq 0) {
                    Add-Check 'service-restart' 'FAIL' 'The bounded job finished or timed out before service restart recovery was demonstrated.'
                }
            }
        }
        'PrepareReboot' {
            if ($null -eq $resolvedJobId) {
                Add-Check 'reboot-job-active' 'NOT_EVALUABLE' 'A bounded job request is required for reboot recovery acceptance.'
            } elseif (-not (Wait-JobActive $resolvedJobId)) {
                $resolvedJobId = $null
            }
            Prepare-RebootChallenge $resolvedJobId
        }
        'VerifyReboot' { Verify-RebootChallenge }
        default { if ($null -ne $resolvedJobId) { $null = Wait-Job $resolvedJobId $false } }
    }
}

$result = Write-AcceptanceReport
$result | ConvertTo-Json -Compress
if (-not $AllowIncomplete) {
    if ($result.verdict -eq 'FAIL') { exit 1 }
    if ($result.verdict -eq 'NOT_EVALUABLE') { exit 2 }
}
