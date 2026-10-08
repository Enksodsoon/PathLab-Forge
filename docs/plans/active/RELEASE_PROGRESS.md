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
| M2 Electron | Integrated, qualification incomplete | Native grants/quit/startup checks pass; final packaged/platform journeys pending |
| M3 runtime | Integrated, release blocked | Portable verifier/Keychain/process containment implemented; actual Mac and redistribution approval pending |
| M4 geometry | Integrated, qualification incomplete | Actual geometry/calibration/native exports implemented; final real-slide journeys pending |
| M5 batches | Integration implementation | Pause/reports/restart/disk-full |
| M6 tools | Integrated foundation and UI; remaining workflows open | Immutable runs/ROI masks/reviews/results delivery; overlays/registration/core journeys pending |
| M7 packs | Integrated lifecycle; authenticated transport in progress | Compatibility/cancel/rollback/offline catalog checks; actual signed catalogs/platform journeys pending |
| M8 teaching | Local drafts/imports/UI integrated | Durable CAS drafts/canonical approvals/bounded imports tested; loaded slide preview, Teaching DZI and scoped publication pending |
| M9 sync | Integrated recovery core; UI in progress | Account binding/atomic snapshots/verified offline files/idempotency tested; final combined offline/production journeys pending |
| M10 distribution | Pending | Licenses/signing/private downloads/upgrades |

No row complete merely because code exists. Record commits, checks and artifact receipts as available. Actual-platform, production, license and signing gates pending until evidenced.

## Integrated evidence, 2026-10-08

- M0 original Windows VSI inspection failure reproduced with a forced source-verification race. Shared inspection update preserves the newer verified fingerprint and status; original regression and full Java suite pass.
- Geometry, runtime and Electron initial batches integrated through `3446f58`; full Java suite passed (261 tests, eight real-fixture skips), frontend 63 passed.
- Teaching backend/UI integrated as `f9dc24c`, `bc0b9a1`, `f2a2756`, `c1a40e2`; worker Java 267 tests (eight skips), frontend authoring/API checks passed. Local approval is deliberately gated until exact teaching pixels are loaded.
- Sync recovery and frozen deterministic-results transfer integrated as `010422f`, `e514dc9`; worker serialized Java 271 tests passed (eight skips). Root local retained-copy renderer and native export integration passed focused checks. This is not actual offline real-slide acceptance.
- Native private startup/lifecycle/feature/export routes integrated as `b78ebd9`; root full Java suite passed. Four desktop policy checks and Node syntax check passed.
- Viewer ownership worker frontend 575 tests and explicit organization-selection browser checks passed; backend and migration completion still pending. PostgreSQL qualification is unavailable while Docker is stopped.
- Windows 11 unsigned internal packaged smoke is historical pre-final integration evidence. Final Windows 10/11 and Intel/Apple Silicon Mac installers remain unqualified.
- No usable current-user Windows signing identity found. Runtime lock still says `PENDING_REVIEW`; do not bypass it. Apple signing/notarization, actual Mac capacity and deployed Viewer commit remain unverified.
