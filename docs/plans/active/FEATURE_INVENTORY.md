# Non-AI release feature inventory

2026-10-08 integration inventory. Each required behavior has an implementation owner. Status refers to current code, not shipment; all actual Windows10/11 and Intel/Apple Silicon journeys remain unqualified unless separately receipted.

| Visible capability / required behavior | Current state | Owner and release gate |
|---|---|---|
| Native launch, private local authorization, sandbox, second-instance focus | Implemented; unsigned Windows smoke | Integration M2; final image and all platforms |
| Native file/folder import | Implemented; focused security checks | Integration M2/M4; actual dialogs/accessibility |
| Dock close vs Quit, active work prompt, About and menus | Implemented; unverified native journey | Integration M2 |
| Missing runtime/backend/lock errors | Partial | Integration M2/M3; actionable diagnostics |
| Window/theme/workspace preservation | Implemented but unverified artifact upgrade | Integration M2/M10 |
| Bundled Java/readers, portable manifest verifier | Implemented; production rights fail closed | Integration M3/Distribution M10 |
| Windows Credential Manager/macOS Keychain | Implemented; Mac unverified | Platform M3 and Sync M9 |
| Runtime channel/hash/architecture enforcement | Implemented; negative tests incomplete | Integration M3/M10 |
| OME/TIFF, SVS, complete VSI/ETS discovery/read/export | Implemented but actual release fixtures incomplete | Integration M0/M3/M4 |
| Optional iSyntax | Deliberately unavailable until approved/qualified | Distribution M3/M10 |
| SDPC | Windows-only; deliberately nonredistributable | Distribution M3/M10 |
| Series/channel/Z/T/projections | Capability scoped; acceptance incomplete | Integration M4; exact preview/export agreement |
| Source/view identity and stale preview rejection | Implemented; combined qualification pending | Integration M4 |
| Rectangle/ellipse/polygon/polyline/freehand/point/text/ruler/angle | Implemented with captured/rendered geometry | Integration M4; edit/save/reload real slides |
| Brush geometry and exact ROI mask | Implemented closed-stroke ROI; Boolean composition incomplete | Integration M4/M6 |
| Selection/edit/label/color/delete/hierarchy | Implemented; scoped tests | Integration M4; keyboard and subtree semantics |
| Anisotropic calibrated measurements / CSV | Implemented; native Save As incomplete | Integration M4 |
| Measured storage capacity | Implemented; managed usage pending | Integration M4 |
| Native verified OME/package Save As/reveal/cancel | Implemented; failure checks, actual journey pending | Integration M4 |
| Result/measurement native export | Incomplete | Integration M4/M6 |
| Crop/downsample/verified artifact reuse | Implemented; actual fidelity gate pending | Integration M5 |
| Durable queue pause/resume | Implemented; persistence and queued cancel checks | Integration M5 |
| Cancellation/retry/mixed batch/restart/disk full | Partial | Integration M5; ten-item forced failures |
| Per-slide batch report | Incomplete | Integration M5 |
| Truthful stages/resource waiting/unknown estimates | Implemented but incomplete actual telemetry qualification | Integration M3/M5 |
| Immutable bounded analysis runs/exact source/ROI/version/parameters | Implemented; SQLite/restart/cancel/source mutation tests | Tools M6 |
| H&E / stain-vector review / bounded normalization | Implemented; native end-to-end pending | Integration M6; qualified brightfield |
| TMA grid/edit/labels/missing/review | Implemented; per-core workflow incomplete | Integration M6 |
| Tissue/QC masks/summaries | Implemented; overlays and artifact qualification pending | Integration M6 |
| Nucleus candidates/geometry/accept/reject | Implemented; approximate candidates only | Integration M6 |
| Registration manual transform | Partial; independent residuals/overlay pending | Integration M6 |
| Accepted private deterministic result delivery | Incomplete | Sync M9 + Integration M6 |
| Non-AI signed Feature Center lifecycle | Implemented; UI progress/enable/rollback pending | Integration M7; authenticated catalog absent |
| AI/training catalog entries | Deliberately unavailable and hidden | Integration M7; no model activation |
| Study drafts/autosave/version/import/export/manual keys/provenance | Worker implementation | Teaching M8 |
| CSV/JSON/QTI/Moodle/Anki bounded import | Worker implementation | Teaching M8; no invented answers |
| Offline faculty preview/checksum/immutable approval | Worker implementation | Teaching M8 |
| Explicit separate static-DZI Teaching delivery | Incomplete | Integration M8 |
| Desktop study authoring discovery/validation/publication | Incomplete | Integration + Viewer M8; teaching authority |
| Viewer courses/invites/scoring/progress/retention/withdrawal | Existing Viewer; owner-aware regression pending | Viewer M1/M8 |
| Owned organization/user libraries and root/child scope | Worker implementation | Viewer M1; SQLite/Postgres/isolation/migrations |
| Reviewed grants/public content and revoked privileges | Worker implementation | Viewer M1; browser/worker/desktop regressions |
| Multi-organization explicit pairing selection | Worker implementation | Viewer M1 |
| Connection-scoped sync/cache/cursor/delivery | Worker implementation | Sync M9; private account changes |
| Complete remote snapshots/deletes/trash and atomic cursors | Worker implementation | Sync M9 |
| Folder navigation/keyboard selection/conflict values | Partial | Integration UI + Sync M9 |
| Durable offline states/hash/atomic activation/uncached local rendering | Worker implementation | Sync M9 + Integration renderer |
| Offset-aware transfer/idempotent create/reconciliation/results distinction | Worker implementation | Sync M9 + Viewer contracts |
| Exact licenses/transitives/notices/corresponding source | Incomplete; approval placeholders remain closed | Distribution M10 |
| Signed Windows and two signed/notarized/stapled DMGs | Incomplete; unsigned internal Windows installer exists | Distribution M10; identities and Mac capacity |
| Data-preserving upgrades/backup/migration/downgrade/uninstall | Incomplete | Distribution M10 |
| Private authenticated downloads/ranges/ETags/history/withdrawal | Incomplete | Viewer/Distribution M10 |
| Manual installer update link | Incomplete | Integration M10 |
| Protected exact-head CI/deploy/live acceptance | Not done | Integration and independent acceptance reviewer |

No dependency license decision, runtime redistribution permission, actual-platform result, production SHA or signing receipt is inferred from tests. All unchanged evidence under docs/evidence remains historical until qualified against the final integrated release commit.
