[CmdletBinding()]
param([string] $StateRoot = 'D:\PathLabData\EvidenceMentor\state')

$ErrorActionPreference = 'Stop'
$endpointPath = Join-Path $StateRoot 'endpoint.json'
$tokenPath = Join-Path $StateRoot 'ipc-token'
if (-not (Test-Path -LiteralPath $endpointPath -PathType Leaf) -or -not (Test-Path -LiteralPath $tokenPath -PathType Leaf)) {
    throw 'PathLab Evidence Mentor is not running.'
}
$endpoint = Get-Content -LiteralPath $endpointPath -Raw | ConvertFrom-Json
if ($endpoint.schema -ne 'pathlab.runner-endpoint/1' -or $endpoint.port -lt 1 -or $endpoint.port -gt 65535) {
    throw 'PathLab Evidence Mentor published an invalid endpoint.'
}
$origin = "http://127.0.0.1:$($endpoint.port)"
$headers = @{ Authorization = 'Bearer ' + (Get-Content -LiteralPath $tokenPath -Raw).Trim() }
$session = Invoke-RestMethod -Method Post -Uri "$origin/v1/dashboard-sessions" -Headers $headers -TimeoutSec 2
if ($session.code -notmatch '^[A-Za-z0-9_-]{40,80}$') { throw 'PathLab Evidence Mentor refused the dashboard session.' }
$url = "$origin/dashboard/#$($session.code)"
$browserCandidates = @(
    'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe',
    'C:\Program Files\Microsoft\Edge\Application\msedge.exe',
    'C:\Program Files\Google\Chrome\Application\chrome.exe',
    'C:\Program Files (x86)\Google\Chrome\Application\chrome.exe'
)
$browser = $browserCandidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
    Select-Object -First 1
if ($browser) {
    Start-Process -FilePath $browser -ArgumentList @('--new-tab', ('"' + $url + '"'))
} else {
    Start-Process -FilePath 'explorer.exe' -ArgumentList ('"' + $url + '"')
}
