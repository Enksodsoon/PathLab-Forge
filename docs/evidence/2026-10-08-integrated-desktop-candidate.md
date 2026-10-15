# Integrated desktop candidate — 2026-10-08

This is local engineering evidence for the non-AI release plan, not release admission.

Forge code commit: `2a506dbe231c6190e9879cf7076308b5e81a179f` on `codex/forge-desktop-release`. Later documentation commits do not change which source built this installer. Forge remote main remains `7de0d9159ee0c999c52de51670b21c86609cf790`. No source or installer was publicly published by this work.

| Check | Result |
|---|---|
| Full Java suite | 336 total: 327 passed, nine explicit real-fixture skips, zero failures/errors, 98 suites |
| Full frontend suite | 91 passed, 15 files |
| TypeScript, production Vite build and bundle budget | Passed; app bundle 199,045 bytes |
| Desktop policy tests | 12 passed |
| Desktop syntax and repository policy | Passed |
| Clean INTERNAL distribution inventory | Passed; `NON_REDISTRIBUTABLE_PENDING_REVIEW` |
| Windows x64 installer assembly | Passed using pnpm 11.9.0 |
| Extracted installer application | Rendered; sandbox/context isolation/no Node, native bridge, private service, rejected renderer grants/invalid IPC, orderly Java shutdown passed |

Installer: `desktop/out/make/squirrel.windows/x64/PathLab Forge-1.0.0-rc.1 Setup.exe`, 367,686,656 bytes. SHA-256: `8357d2dced90c2ac6f2aa4dee17b947845223b08276a9e5179f7cd5b2424200e`. The final app receipt records `sourceDirty: false`, `payloadBound: true` and the same source commit/hash. It is unsigned and internal. Reader staging, reader signing and reader verification were explicitly skipped without approved inputs; this does not establish mandatory-format support in the shipped payload.

Ignored exact-run evidence:

- `build/integrated-offline-pack-final-check.txt`
- `build/frontend-integrated-feature-import.txt`
- `build/integrated-offline-pack-make.txt`
- `build/distribution-inputs/final-app-win32-x64.json`
- `build/distribution-inputs/integrated-internal-smoke-final.txt`
- Rendered screenshot: `C:\Users\enkso\AppData\Local\Temp\forge-desktop-smoke-gyEKmz\desktop-smoke.png`, visually inspected.

## Final access and source-preservation corrections

Fresh unpaired users previously could not install deterministic packs because installation required a privileged Viewer pairing. `e7cb83e` reuses the existing installer with native-selected signed catalog/archive inputs. `eebc2a9` adds distinct native feature-file grants and the bounded CSRF-protected import route/UI. Catalog signatures, pinned trust key, allowed non-AI identities, archive size/hash, copied-byte rehash, platform/resource compatibility, safe extraction, self-test, immutable receipt and activation checks remain required. No Viewer role, library scope or authority is granted by local import.

Independent review executed unpaired import/restart/rollback, invalid-input rejection, activation/tamper recovery, cancellation and authenticated-origin transport checks. Root then identified input collisions with managed cleanup paths. A real signed-fixture probe reproduced deletion of the selected partial archive, staging archive and staging catalog. `2a506db` rejects both selected inputs within the managed feature directory, including normalized/canonical aliases, before mutation. The reviewer reran all three cases: both inputs remained byte-identical and no pack activated. The integrated regression and native-grant/CSRF/signature HTTP regression pass.

## Open release gates

Windows 10, Intel Mac and Apple Silicon Mac are not qualified. Native dialogs, accessibility, actual upgrade/uninstall/recovery, six-core/8-GB absolute resource limits and every mandatory real-slide journey need actual-artifact evidence. Windows signing identity, Apple signing/notarization capacity, approved exact runtime rights/readers, production catalog key/packs and corresponding third-party source remain absent or pending review.

The M5 forced-termination suite observed one Windows SQLite `SQLITE_IOERR_TRUNCATE` on reopening WAL state. Subsequent passing runs do not close that recovery qualification issue; source/database files were preserved. See `2026-10-08-m5-inspection-intent.md`.

Viewer ownership/download/study changes remain local until protected CI, PostgreSQL migration/isolation, backup/restore/rollback, protected deployment and authenticated production journeys pass. Git main is not proof of a deployed version. Release catalogs remain empty until immutable signed artifacts and real acceptance receipts qualify. AI/training/research functionality stays excluded and disabled.

Viewer local verification completed on stable source/receipt commit `27c081886eac3d00a06b718fe57ec1aea93c4b4a`, integrating fresh main `81fcc80c5f2de81a7ff47f526aaf8f24742637fb`: 1,481 backend passed/111 skipped, 578 frontend passed, six browser journeys, 492 load passed/12 skipped. Final evidence-only head `57b12a1df5b3ac6889606198da757292799af05f` is clean. A source-traced authenticated-download membership restriction was fixed in the shared guard, without broadening library or pairing permissions. These local results do not close the deployment/platform gates above.
