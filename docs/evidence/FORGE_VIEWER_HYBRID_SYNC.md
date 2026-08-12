# Forge–Viewer Hybrid Sync Evidence

Date: 2026-08-12

## Implemented boundary

- Private library/folder listing and durable change cursor.
- Authenticated cached DZI/thumbnail viewing in Forge.
- Explicit resumable verified OME offline copy and safe removal.
- Revision-safe private metadata/folder updates.
- Existing private annotation read/batch proxy.
- Per-field conflict preservation with Keep local or Keep Viewer.
- Five-second polling only while the Viewer library is open.

No WebSocket, daemon, filesystem watcher, public sync, pixel replacement, or new runtime was added.

## Verification

- Forge: 184 Java tests (181 pass, 3 skip), 45 frontend tests pass, Gradle check/installDist,
  TypeScript production build, and bundle budget pass.
- Viewer: 458 tests collected (454 pass, 4 skip) and Ruff pass.
- Contract fixtures: five byte-identical JSON files in both repositories. Library fixture SHA-256:
  `e12badd287cd494e04bd86fd8a145fa063513fb8f12c63833e8569c4dd8ebcea`.
- Browser: desktop live route renders fail-closed against a stale Viewer; 768 x 900 has no
  horizontal overflow and no console errors.

## Current local limitation

The Viewer process currently paired on this workstation predates `desktop-sync/v1` and returns
404 for the library endpoint. Forge now reports **Viewer update required** instead of claiming a
successful sync. Restarting/deploying Viewer is a separate operation; production was not changed.
