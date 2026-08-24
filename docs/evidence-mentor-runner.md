# Evidence Mentor autonomous service

`EvidenceMentorRunner` is an offline Windows-service workload. It binds only to
`127.0.0.1` on a Windows-assigned port, atomically publishes
`state/endpoint.json`, and authenticates API clients with the existing 256-bit
token in `state/ipc-token`. The endpoint file never contains the token.

The SQLite WAL queue migrates v1 records in place. It persists the execution
lane, monotonic work units, verified checkpoint identity, lease heartbeat,
rolling throughput, ETA, retry schedule, stable failure classification, and
request/pack/final-artifact checksums. Exactly one GPU job and one low-priority
CPU/I/O job may execute. Live external workers renew their lease no less often
than every 10 seconds; only transient I/O failures receive 5-second, 30-second,
and 2-minute retries.

## Dashboard

Forge or the operator launcher uses the bearer token to request a single-use
60-second code and opens `/dashboard/#<code>`. The dashboard exchanges it once
for an HttpOnly, SameSite=Strict boot-scoped cookie, removes the fragment, and
uses a per-session CSRF token for mutations. It polls with ETags every two
seconds while active and ten seconds while idle.

The dependency-free page exposes operational metadata only. It can pause or
resume new claims, request checkpoint-safe cancellation, and retry failed or
cancelled work after immutable-request verification. It cannot delete jobs,
change priority, activate packs, approve evidence, or publish results. Dashboard
and telemetry failures never alter processing state.

## Service installation

First build a versioned distribution:

```powershell
.\gradlew.bat installDist
```

Then run the administrator-approved installer with a Java 17 runtime:

```powershell
.\scripts\evidence-mentor-service.ps1 -Action Install -Version '2.1.0' `
  -DistributionPath '.\build\install\pathlab-forge' -JavaHome 'C:\Path\To\jdk-17'
```

The installer downloads WinSW 2.12.0 from its official release URL and requires
SHA-256 `05b82d46ad331cc16bdc00de5c6332c1ef818df8ceefcd49c726553209b3a0da`.
It installs `PathLabEvidenceMentor` as delayed-auto-start under
`NT AUTHORITY\LocalService`, stages runtimes side-by-side under
`C:\ProgramData\PathLab\EvidenceMentor\runtime\<version>`, keeps state under
`D:\PathLabData\EvidenceMentor\state`, applies service-SID ACLs and outbound-deny
rules, enables the restricted service identity's per-service SID, and rolls back
the active configuration if health checks fail.

Use `-Action Upgrade` with a new immutable version. `-Action Uninstall` removes
the service, firewall rules, and Start Menu shortcut while preserving queues,
checkpoints, signing material, evidence, and model packs.

If an elevated install is interrupted after staging but before activation, rerun
the identical command with `-ReuseStagedRuntime`. The installer hashes every
application and Java runtime file against the requested inputs before reuse. It
refuses missing, extra, modified, or mismatched files and never overwrites the
staged version.

## Host acceptance and the definition of done

Installation is not acceptance. Run the installed acceptance harness from an
administrator PowerShell window and retain its immutable JSON and Markdown
reports under `D:\PathLabData\EvidenceMentor\state\acceptance`.

Start with the non-disruptive inspection:

```powershell
& 'C:\ProgramData\PathLab\EvidenceMentor\Test-PathLab-Evidence-Service.ps1' `
  -Mode Inspect
```

Stage a small, rights-approved GPU-pack request under the protected state root.
Its public acceptance ID must match `acceptance-<8-64 lowercase hex chars>`.
The bundled staging command creates a deterministic synthetic H&E tile cache
and a benchmark-only, not-evaluable DINOv2 Session 0 manifest. It does not
activate or qualify the H&E model:

```powershell
.\scripts\make-dinov2-runtime-portable.ps1 `
  -BasePython 'C:\Path\To\Python312'
.\scripts\stage-dinov2-session0-acceptance.ps1
```

The one-time portability step copies the Python base needed by the external
pack into protected model storage, removes the user-profile runtime dependency,
and extends the checksum ledger. It refuses to overwrite an existing portable
base. Forge itself still contains no Python, PyTorch, CUDA, or model weights.

Then prove service restart recovery while that bounded job is active:

```powershell
& 'C:\ProgramData\PathLab\EvidenceMentor\Test-PathLab-Evidence-Service.ps1' `
  -Mode ServiceRestart `
  -JobRequestPath 'D:\PathLabData\EvidenceMentor\state\acceptance\gpu-session0-v1\request.json' `
  -JobId 'acceptance-0123abcd' `
  -TimeoutMinutes 10
```

Reboot acceptance is deliberately split into two operator-controlled commands.
The harness never initiates a reboot:

```powershell
& 'C:\ProgramData\PathLab\EvidenceMentor\Test-PathLab-Evidence-Service.ps1' `
  -Mode PrepareReboot `
  -JobRequestPath 'D:\PathLabData\EvidenceMentor\state\acceptance\gpu-session0-v1\request.json' `
  -JobId 'acceptance-89abcdef'

# Reboot Windows manually while the bounded job is active. Do not log in first.

& 'C:\ProgramData\PathLab\EvidenceMentor\Test-PathLab-Evidence-Service.ps1' `
  -Mode VerifyReboot `
  -TimeoutMinutes 10
```

Finally aggregate the evidence:

```powershell
& 'C:\ProgramData\PathLab\EvidenceMentor\Test-PathLab-Evidence-Service.ps1' `
  -Mode Summary
```

The autonomous-service phase is done only when the newest `Summary` report has
schema `pathlab.service-acceptance/1`, verdict `PASS`, and no required check is
`FAIL` or `NOT_EVALUABLE`. This includes LocalService identity, delayed startup
and recovery policy, pinned WinSW, ACLs, firewall coverage, dynamic authenticated
IPC, one-time dashboard sessions, P2000 visibility, a completed GPU-lane job,
service-restart recovery, and reboot continuation of an active bounded job.
The reboot continuation check additionally requires the job's terminal update
to occur after the new OS boot time; a job that finished before reboot cannot
satisfy the gate.
Mode-specific `PASS` reports are evidence fragments and are not a completion
claim. `-AllowIncomplete` is intended only for safe diagnostics and cannot turn
an incomplete report into passing evidence.

## Model boundary

The deterministic cell/IHC baselines are runnable only with non-identifying
`qualification-<hex>` job IDs. Their `experimental` status is not pilot
eligibility. The current cell fallback is connected-component morphometry, not
validated watershed instance segmentation. Current IHC output is generic
within-image H/DAB description; marker-specific requests explicitly fall back,
and PD-L1 refuses compartment claims until reviewed geometry is carried by the
request contract.

DINOv2-small remains a `not_evaluable` executable candidate until its
cross-tissue held-out cohort is complete. Hibou-B and HoVer-Net remain
`not_evaluable` until exact, rights-approved workers and artifacts pass
qualification. Only `qualified` private-research packs may process ordinary
staff/demo jobs. H&E, cells, IHC, special stains, cytology, and later Atlas
tracks stay independently signed and fail closed; none may emit diagnosis,
clinical categories, TPS/CPS, prognosis, or treatment guidance.
