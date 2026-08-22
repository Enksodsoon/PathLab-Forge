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
.\scripts\evidence-mentor-service.ps1 -Action Install -Version '2.0.0' `
  -DistributionPath '.\build\install\pathlab-forge' -JavaHome 'C:\Path\To\jdk-17'
```

The installer downloads WinSW 2.12.0 from its official release URL and requires
SHA-256 `05b82d46ad331cc16bdc00de5c6332c1ef818df8ceefcd49c726553209b3a0da`.
It installs `PathLabEvidenceMentor` as delayed-auto-start under
`NT AUTHORITY\LocalService`, stages runtimes side-by-side under
`C:\ProgramData\PathLab\EvidenceMentor\runtime\<version>`, keeps state under
`D:\PathLabData\EvidenceMentor\state`, applies service-SID ACLs and outbound-deny
rules, and rolls back the active configuration if health checks fail.

Use `-Action Upgrade` with a new immutable version. `-Action Uninstall` removes
the service, firewall rules, and Start Menu shortcut while preserving queues,
checkpoints, signing material, evidence, and model packs.

## Model boundary

The deterministic cell/IHC baseline is runnable. DINOv2-small remains an
experimental executable baseline until its cross-tissue held-out cohort is
complete. Hibou-B and HoVer-Net remain `not_evaluable` until exact,
rights-approved workers and artifacts pass qualification. H&E, cells, IHC,
special stains, cytology, and later Atlas tracks stay independently signed and
fail closed; none may emit diagnosis, clinical categories, TPS/CPS, prognosis,
or treatment guidance.
