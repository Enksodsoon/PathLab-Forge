# Release integration register

Baseline 2026-10-08:
- Forge origin/main: 7de0d9159ee0c999c52de51670b21c86609cf790.
- Selected reader baseline: 32a4a36 (six reader/runtime commits atop main).
- Integration branch codex/forge-desktop-release.
- Viewer origin/main: fac6476249ed287bb2afe8def1b0bcc9558104d7.
- Deployed Viewer: unverified, never inferred from Git.
- Explicit Adoptium JDK17 and bundled Node/pnpm for checks.
- AI/ADAPT/TRACE branches excluded; teaching/PIVOT selective references only.

| Milestone | State | Required evidence |
|---|---|---|
| M0 baseline | In progress | Tests/VSI reproduction/feature inventory/fixtures |
| M1 ownership | Worker implementation | Isolation/migration/rollback/Viewer CI |
| M2 Electron | Worker implementation | Packaged lifecycle/IPC/platforms |
| M3 runtime | Worker implementation | Portability/credentials/telemetry/rights |
| M4 geometry | Worker implementation | Capture/edit/save/scopes/calibration |
| M5 batches | Integration implementation | Pause/reports/restart/disk-full |
| M6 tools | Pending | Immutable runs/all deterministic journeys |
| M7 packs | Pending | Compatibility/cancel/rollback/offline catalog |
| M8 teaching | Pending | Draft/import/DZI/scoped approved publication |
| M9 sync | Pending | Isolation/snapshots/conflicts/offline/retries |
| M10 distribution | Pending | Licenses/signing/private downloads/upgrades |

No row complete merely because code exists. Record commits, checks and artifact receipts as available. Actual-platform, production, license and signing gates pending until evidenced.
