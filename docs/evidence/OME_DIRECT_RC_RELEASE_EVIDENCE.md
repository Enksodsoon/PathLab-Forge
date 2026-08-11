# Direct OME local release-candidate evidence

## Result

The local release-candidate path is usable and remains disabled remotely: no push,
merge, deployment, signing, or production activation was performed.

The installed Windows distribution completed three isolated process-level trials of:

`Forge -> exact ome-dynamic-v1 negotiation -> factor-2 OME -> resumable upload ->
Viewer persisted SHA verification -> ready_private -> native JPEG OME tile`

The same trials restarted Viewer with direct OME disabled and proved automatic
`prepared-v2` selection, package creation, SHA verification, private readiness, and
stored DZI preview. Every direct trial stored one OME and zero DZI files.

## Real-slide comparison

A private user-provided VSI/ETS set was used without committing its identity, path,
hash, geometry, tiles, or raw logs. Direct and prepared measurements used fresh Forge
state, the same selected full-resolution series, the same host, and the same codec
profile.

| Metric | Direct OME | Prepared-v2 |
| --- | ---: | ---: |
| Conversion to package-ready | 199,788 ms | 374,619 ms |
| Retained artifact bytes | 500,309,132 | 1,850,400,059 |
| Peak process-tree working set | 4,110,106,624 | 4,172,959,744 |
| Peak managed workspace | 500,312,729 | 3,175,606,914 |

Direct OME was 46.67% faster and retained 72.96% fewer derivative bytes. This passes
the release plan's comparative goals of 30% faster and 60% less retained derivative
storage. Both full-slide results exceed the older generic 72-second/400-MB absolute
benchmark limits; those limits are reported, not weakened.

The real file gate found and corrected a factor-2 compatibility defect: QuPath emits
valid additional globally aligned overview SubIFDs, while libvips normally stops at a
single-tile level. Forge now requires complete Viewer coverage, bounds maximum depth,
and validates every required native level. Viewer independently indexes and validates
all stored levels before readiness.

## Concurrency and recovery

Across the three deterministic process trials, the 1, 10, 25, and 50-client tile
smokes returned zero errors. Worst observed p95 values were 25.977, 51.862, 132.164,
and 234.788 ms respectively; worst p99 values were 25.977, 51.862, 134.687, and
244.617 ms.

Focused recovery checks cover upload interruption with offset resume, retry of failed
finalization without recreating or retransmitting an artifact, exact persisted-SHA
match, rejection of missing/mismatched persisted SHA, malformed or incomplete exact
profiles, artifact reuse, source revalidation, corrupted/truncated Viewer ingest,
storage admission, credential revocation, and the joint advertisement/acceptance kill
switch. Temporary process trees are terminated on success and failure.

## Repository and product gates

- Viewer backend: 437 passed, 4 skipped.
- Viewer frontend: 226 passed; ESLint and production TypeScript/Vite build passed.
- Viewer Ruff and strict MyPy passed; a fresh database migrated to Alembic head
  `20260730_0014`.
- Forge Gradle `clean check installDist` passed: 151 tests, 0 failures, 2 skipped.
- Forge frontend: 33 passed; production bundle gate passed.
- Forge repository policy scan passed.
- Installed runtime browser smoke reached `/app`, rendered library/import/viewer and
  connection controls, opened and cancelled the real pairing dialog, and emitted no
  browser console errors.

## Local launch

Bio-Formats remains owner-supplied pending redistribution review:

```powershell
$env:PATHLAB_FORGE_BFTOOLS = "<Bio-Formats 8.5 directory>"
.\build\install\pathlab-forge\bin\pathlab-forge.bat --serve
```

Use the one-time authorization URL printed by Forge. The permanent loopback app is
`http://127.0.0.1:51274/app`. This is an installed local runtime, not a signed or
public installer.

## Evidence boundary

Machine-readable source-safe metrics are in `ome-direct-rc-metrics.json`. Private raw
reports, fixture identifiers, coordinates, hashes, generated artifacts, and process
logs remain outside Git.
