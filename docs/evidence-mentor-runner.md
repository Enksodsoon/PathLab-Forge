# Evidence Mentor runner

`EvidenceMentorRunner` is a standalone per-user process. It binds only to
`127.0.0.1`, requires a 256-bit bearer token from `ipc-token`, and owns the
SQLite WAL queue, leases, checkpoints, signing key, and result artifacts.

Install from a versioned Forge distribution:

```powershell
.\scripts\evidence-mentor-task.ps1 -RuntimeRoot 'C:\Path\To\pathlab-forge'
```

Default state is `D:\PathLabData\EvidenceMentor\state` when the data drive is
present, otherwise `%LOCALAPPDATA%\PathLab\EvidenceMentor\state`. Analysis is
offline. Acquisition is outside this process. Current runnable pack is the
deterministic cell/IHC baseline; H&E encoder requests fail closed until an
approved, checksum-matched encoder artifact is installed.

Rollback removes the scheduled task but preserves queue, evidence, and keys:

```powershell
.\scripts\evidence-mentor-task.ps1 -RuntimeRoot 'C:\Path\To\pathlab-forge' -Uninstall
```
