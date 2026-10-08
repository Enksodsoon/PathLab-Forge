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
| M1 ownership | Viewer local qualification complete; release gates open | SQLite isolation/backfill/restore and independent review passed; PostgreSQL/protected CI/deployment pending |
| M2 Electron | Integrated, qualification incomplete | Native grants/quit/startup checks pass; final packaged/platform journeys pending |
| M3 runtime | Integrated, release blocked | Portable verifier/Keychain/process containment implemented; actual Mac and redistribution approval pending |
| M4 geometry | Integrated, qualification incomplete | Actual geometry/calibration/native exports implemented; final real-slide journeys pending |
| M5 batches | Durable backend/HTTP/UI integrated | Synthetic mixed/cancel/retry/stage recovery pass; pre-inspection intent and actual ten-slide/disk-full acceptance open |
| M6 tools | Complete TMA/registration/mask result workflows integrated | Durable runs/reviews/core provenance/held-out residuals/real overlays tested; brush Boolean composition and real-slide/native acceptance open |
| M7 packs | Integrated lifecycle and authenticated transport | Compatibility/cancel/rollback/offline catalog checks; actual hosted signed catalogs/platform journeys pending |
| M8 teaching | Drafts/imports/Teaching generation+upload/scoped publication integrated | Prepared package-SHA results tests pass; exact offline loaded-pixel association/preview in progress; real Viewer publication open |
| M9 sync | Integrated recovery core and UI | Account binding/atomic snapshots/verified offline files/idempotency tested; Viewer negotiated creation and final offline/production journeys pending |
| M10 distribution | Producer/activation gates integrated; release blocked | Exact-byte inventory/source/signing pipeline exists; rights/signing/Mac acceptance/private downloads/upgrades pending |

No row complete merely because code exists. Record commits, checks and artifact receipts as available. Actual-platform, production, license and signing gates pending until evidenced.

## Integrated evidence, 2026-10-08

- M0 original Windows VSI inspection failure reproduced with a forced source-verification race. Shared inspection update preserves the newer verified fingerprint and status; original regression and full Java suite pass.
- Geometry, runtime and Electron initial batches integrated through `3446f58`; full Java suite passed (261 tests, eight real-fixture skips), frontend 63 passed.
- Teaching backend/UI integrated as `f9dc24c`, `bc0b9a1`, `f2a2756`, `c1a40e2`; worker Java 267 tests (eight skips), frontend authoring/API checks passed. Local approval is deliberately gated until exact teaching pixels are loaded.
- Sync recovery and frozen deterministic-results transfer integrated as `010422f`, `e514dc9`; worker serialized Java 271 tests passed (eight skips). Root local retained-copy renderer and native export integration passed focused checks. This is not actual offline real-slide acceptance.
- Native private startup/lifecycle/feature/export routes integrated as `b78ebd9`; root full Java suite passed. Four desktop policy checks and Node syntax check passed.
- Viewer ownership local head `9b3305ba`: frontend 575 tests, explicit organization-selection browser journeys and independent review passed; full backend run plus two repaired-test reruns yielded 1,454 passing unique cases, 110 skipped. SQLite migration/backup/restore passed. PostgreSQL qualification is unavailable while Docker is stopped; this is not a single all-green backend run or deployment receipt.
- Windows 11 unsigned internal packaged smoke is historical pre-final integration evidence. Final Windows 10/11 and Intel/Apple Silicon Mac installers remain unqualified.
- No usable current-user Windows signing identity found. Runtime lock still says `PENDING_REVIEW`; do not bypass it. Apple signing/notarization, actual Mac capacity and deployed Viewer commit remain unverified.

## Additional integrated checks

- `7978a2f` captures one immutable credential for scope validation and HTTP dispatch; a real PATCH regression checks that account switching cannot send the old request using the new account's token.
- `7a77806` removes poisoned download partials after hash failure while retaining valid network-resume partials; transfer locking keeps status, cancellation and retained-copy viewing responsive. Synthetic failure/retry/isolation checks passed.
- `61684b2` renders reviewed TMA/candidate objects in the actual slide coordinate projection, in bounded 2,000-object pages. This remains read-only and preserves original analysis outputs.
- `41443f3` adds independent registration check pairs, target-pixel residuals and a bounded source/target overlay with opacity control. Saved exact-plane polyline/point annotations provide manually placed landmarks. Fit points cannot validate their own fit. Focused Java checks and production TypeScript/build/budget passed; frontend suite passed 80 tests before the final saved-landmark selector, then its focused three tests and TypeScript passed afterward. Real-slide registration acceptance remains open.
- Distribution producer batches integrated as `62c283c`, `3fdb04e`, `808e587`: pinned ASAR channel/trust key/service inventory, Electron integrity fuses, staged native signing and exact final-app/source/review binding. Worker unsigned Windows smoke/tamper receipts are internal-only and must be repeated on the final integration head. No production signing or rights approval is generated by these scripts.

- M5 durable manifest, per-item source/configuration snapshots, retry/cancel/report and startup-before-dispatch recovery integrated as `5ffbcab`/`387c1f8`. Local batch HTTP/native CSV export/restart tests passed; real ten-slide failure qualification remains open.
- M6 TMA reviewed-core hierarchy, current-review core provenance, paged analysis history and actual tissue/H&E mask overlays integrated as `3800edd`/`cdb49b4`/`404c734`. Frontend full suite passed 85 cases, and focused HTTP/geometry/analysis tests passed. Actual qualified slide/tool journeys remain open.
- M8 explicit static-DZI generation and scoped study discovery/publication proxy integrated as `1c4246d`. `4af0a05` makes prepared upload reachable only through explicit Teaching delivery, retains direct OME defaults, and binds image/results to the package SHA. Both modes' synthetic HTTP transfers, structured results and completed-delivery dedupe passed.
- Independent packaging review's three concrete findings were repaired and integrated as `04ef244`: staged reader activation, actual final installer payload verification, and pnpm linked/transitive inventories. `b70a3c1` adds fixed actionable startup screens. Twelve desktop checks passed. Worker's actual unsigned installer extraction/smoke/tamper evidence is for its own earlier commit, not the combined release head.
- Locked WAL-safe metadata backup/downgrade gate and recovery instructions integrated as `01a6c3f`/`71294b4`. Independent review reproduced same-version orphan WAL data loss; `b8cd8e3` fixes the early return and its real committed-WAL regression passes. No schema writer runs before this gate in installed mode. Real native power-loss/upgrade/restore acceptance remains open.
- `7d332df` exposes actual volume capacity and async bounded managed regular-file usage, preserves unscoped annotations without guessed calibration, adds raw geometry to measurement export, and separates analysis paging cursors from displayed result count. Focused Java checks and App49 passed; bounded scans disclose partial totals explicitly.
